// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jps.incremental;

import com.dynatrace.hash4j.hashing.HashSink;
import com.dynatrace.hash4j.hashing.HashStream64;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.SmartList;
import com.intellij.util.containers.FileCollectionFactory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.ProjectPaths;
import org.jetbrains.jps.api.GlobalOptions;
import org.jetbrains.jps.builders.*;
import org.jetbrains.jps.builders.java.ExcludedJavaSourceRootProvider;
import org.jetbrains.jps.builders.java.JavaModuleBuildTargetType;
import org.jetbrains.jps.builders.java.JavaSourceRootDescriptor;
import org.jetbrains.jps.builders.storage.BuildDataPaths;
import org.jetbrains.jps.cmdline.ProjectDescriptor;
import org.jetbrains.jps.incremental.relativizer.PathRelativizerService;
import org.jetbrains.jps.incremental.storage.ProjectStamps;
import org.jetbrains.jps.indices.IgnoredFileIndex;
import org.jetbrains.jps.indices.ModuleExcludeIndex;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.java.*;
import org.jetbrains.jps.model.java.compiler.JpsJavaCompilerConfiguration;
import org.jetbrains.jps.model.java.compiler.ProcessorConfigProfile;
import org.jetbrains.jps.model.java.impl.JpsJavaDependenciesEnumeratorImpl;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.module.JpsModuleDependency;
import org.jetbrains.jps.model.module.JpsTypedModuleSourceRoot;
import org.jetbrains.jps.service.JpsServiceManager;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.jetbrains.jps.incremental.FileHashUtilKt.getFileHash;
import static org.jetbrains.jps.incremental.FileHashUtilKt.normalizedPathHashCode;

/**
 * Describes a step of compilation process which produces JVM *.class files from files in production/test source roots of a Java module.
 * These targets are built by {@link ModuleLevelBuilder} and they are the only targets that can have circular dependencies on each other.
 */
public final class ModuleBuildTarget extends JVMModuleBuildTarget<JavaSourceRootDescriptor> implements BuildTargetHashSupplier {
  private static final Logger LOG = Logger.getInstance(ModuleBuildTarget.class);

  public static final Boolean REBUILD_ON_DEPENDENCY_CHANGE = Boolean.valueOf(
    System.getProperty(GlobalOptions.REBUILD_ON_DEPENDENCY_CHANGE_OPTION, "true")
  );
  private final JavaModuleBuildTargetType myTargetType;

  public ModuleBuildTarget(@NotNull JpsModule module, @NotNull JavaModuleBuildTargetType targetType) {
    super(targetType, module);
    myTargetType = targetType;
  }

  public @Nullable File getOutputDir() {
    return JpsJavaExtensionService.getInstance().getOutputDirectory(myModule, myTargetType.isTests());
  }

  @Override
  public @NotNull Collection<File> getOutputRoots(@NotNull CompileContext context) {
    Collection<File> result = new SmartList<>();
    final File outputDir = getOutputDir();
    if (outputDir != null) {
      result.add(outputDir);
    }
    final JpsModule module = getModule();
    final JpsJavaCompilerConfiguration configuration = JpsJavaExtensionService.getInstance().getCompilerConfiguration(module.getProject());
    final ProcessorConfigProfile profile = configuration.getAnnotationProcessingProfile(module);
    if (profile.isEnabled()) {
      final File annotationOut = ProjectPaths.getAnnotationProcessorGeneratedSourcesOutputDir(module, isTests(), profile);
      if (annotationOut != null) {
        result.add(annotationOut);
      }
    }
    return result;
  }

  @Override
  public boolean isTests() {
    return myTargetType.isTests();
  }

  @Override
  public @NotNull Collection<BuildTarget<?>> computeDependencies(@NotNull BuildTargetRegistry targetRegistry, @NotNull TargetOutputIndex outputIndex) {
    JpsJavaDependenciesEnumeratorImpl enumerator = (JpsJavaDependenciesEnumeratorImpl)JpsJavaExtensionService.dependencies(myModule).compileOnly();
    if (!isTests()) {
      enumerator.productionOnly();
    }
    final ArrayList<BuildTarget<?>> dependencies = new ArrayList<>();
    enumerator.processDependencies(dependencyElement -> {
      if (dependencyElement instanceof JpsModuleDependency) {
        JpsModule depModule = ((JpsModuleDependency)dependencyElement).getModule();
        if (depModule != null) {
          JavaModuleBuildTargetType targetType;
          if (myTargetType.equals(JavaModuleBuildTargetType.PRODUCTION) && enumerator.isProductionOnTests(dependencyElement)) {
            targetType = JavaModuleBuildTargetType.TEST;
          }
          else {
            targetType = myTargetType;
          }
          dependencies.add(new ModuleBuildTarget(depModule, targetType));
        }
      }
      return true;
    });
    if (isTests()) {
      dependencies.add(new ModuleBuildTarget(myModule, JavaModuleBuildTargetType.PRODUCTION));
    }
    final Collection<ModuleBasedTarget<?>> moduleBased = targetRegistry.getModuleBasedTargets(
      getModule(), isTests() ? BuildTargetRegistry.ModuleTargetSelector.TEST : BuildTargetRegistry.ModuleTargetSelector.PRODUCTION
    );
    for (ModuleBasedTarget<?> target : moduleBased) {
      if (target != this && target.isCompiledBeforeModuleLevelBuilders()) {
        dependencies.add(target);
      }
    }
    dependencies.trimToSize();
    return dependencies;
  }

  @Override
  public @NotNull List<JavaSourceRootDescriptor> computeRootDescriptors(@NotNull JpsModel model, @NotNull ModuleExcludeIndex index, @NotNull IgnoredFileIndex ignoredFileIndex, @NotNull BuildDataPaths dataPaths) {
    List<JavaSourceRootDescriptor> roots = new ArrayList<>();
    JavaSourceRootType type = isTests() ? JavaSourceRootType.TEST_SOURCE : JavaSourceRootType.SOURCE;
    Iterable<ExcludedJavaSourceRootProvider> excludedRootProviders = JpsServiceManager.getInstance().getExtensions(ExcludedJavaSourceRootProvider.class);
    final JpsJavaCompilerConfiguration compilerConfig = JpsJavaExtensionService.getInstance().getCompilerConfiguration(myModule.getProject());

    roots_loop:
    for (JpsTypedModuleSourceRoot<JavaSourceRootProperties> sourceRoot : myModule.getSourceRoots(type)) {
      if (index.isExcludedFromModule(sourceRoot.getFile(), myModule)) {
        continue;
      }
      for (ExcludedJavaSourceRootProvider provider : excludedRootProviders) {
        if (provider.isExcludedFromCompilation(myModule, sourceRoot)) {
          continue roots_loop;
        }
      }

      String packagePrefix = sourceRoot.getProperties().getPackagePrefix();

      // consider annotation processors output for generated sources, if contained under some source root
      Set<Path> excludes = computeRootExcludes(sourceRoot.getPath(), index);
      ProcessorConfigProfile profile = compilerConfig.getAnnotationProcessingProfile(myModule);
      if (profile.isEnabled()) {
        File outputIoDir = ProjectPaths.getAnnotationProcessorGeneratedSourcesOutputDir(myModule, JavaSourceRootType.TEST_SOURCE == sourceRoot.getRootType(), profile);
        if (outputIoDir != null) {
          Path outputDir = outputIoDir.toPath();
          if (sourceRoot.getPath().startsWith(outputDir)) {
            excludes = FileCollectionFactory.createCanonicalPathSet(excludes);
            excludes.add(outputDir);
          }
        }
      }
      FileFilter filterForExcludedPatterns = index.getModuleFileFilterHonorExclusionPatterns(myModule);
      roots.add(JavaSourceRootDescriptor.createJavaSourceRootDescriptor(sourceRoot.getFile(), this, false, false, packagePrefix, excludes, filterForExcludedPatterns));
    }
    return roots;
  }

  @Override
  public @NotNull String getPresentableName() {
    return "Module '" + getModule().getName() + "' " + (myTargetType.isTests() ? "tests" : "production");
  }

  @Override
  @ApiStatus.Internal
  public void computeConfigurationDigest(@NotNull ProjectDescriptor projectDescriptor, @NotNull HashSink hash) {
    JpsModule module = getModule();
    PathRelativizerService relativizer = projectDescriptor.dataManager.getRelativizer();

    StringBuilder logBuilder = LOG.isDebugEnabled() ? new StringBuilder() : null;

    getDependenciesFingerprint(logBuilder, relativizer, hash);

    List<JavaSourceRootDescriptor> roots = projectDescriptor.getBuildRootIndex().getTargetRoots(this, null);
    for (JavaSourceRootDescriptor root : roots) {
      String path = relativizer.toRelative(root.rootFile.toString());
      if (logBuilder != null) {
        logBuilder.append(path).append('\n');
      }
      normalizedPathHashCode(path, hash);
    }
    hash.putInt(roots.size());

    LanguageLevel level = JpsJavaExtensionService.getInstance().getLanguageLevel(module);
    if (level == null) {
      hash.putInt(0);
    }
    else {
      if (logBuilder != null) {
        logBuilder.append(level.name()).append("\n");
      }
      hash.putString(level.name());
    }

    JpsJavaCompilerConfiguration config = JpsJavaExtensionService.getInstance().getCompilerConfiguration(module.getProject());
    String bytecodeTarget = config.getByteCodeTargetLevel(module.getName());
    if (bytecodeTarget == null) {
      hash.putInt(0);
    }
    else {
      if (logBuilder != null) {
        logBuilder.append(bytecodeTarget).append('\n');
      }
      hash.putString(bytecodeTarget);
    }

    CompilerEncodingConfiguration encodingConfig = projectDescriptor.getEncodingConfiguration();
    String encoding = encodingConfig.getPreferredModuleEncoding(module);
    if (encoding == null) {
      hash.putInt(0);
    }
    else {
      if (logBuilder != null) {
        logBuilder.append(encoding).append("\n");
      }
      hash.putString(encoding);
    }

    if (logBuilder == null) {
      return;
    }

    Path configurationTextFile = projectDescriptor.getTargetsState().getDataPaths().getTargetDataRootDir(this).resolve("config.dat.debug.txt");
    @NonNls String oldText;
    try {
      oldText = Files.readString(configurationTextFile);
    }
    catch (IOException e) {
      oldText = null;
    }
    String newText = logBuilder.toString();
    if (!newText.equals(oldText)) {
      if (oldText != null && hash instanceof HashStream64) {
        LOG.debug("Configuration differs from the last recorded one for " + getPresentableName() + ".\nRecorded configuration:\n" + oldText +
                  "\nCurrent configuration (hash=" + ((HashStream64)hash).getAsLong() + "):\n" + newText);
      }
      try {
        Files.createDirectories(configurationTextFile.getParent());
        Files.writeString(configurationTextFile, newText);
      }
      catch (IOException e) {
        LOG.debug(e);
      }
    }
  }

  private void getDependenciesFingerprint(@Nullable StringBuilder logBuilder,
                                          @NotNull PathRelativizerService relativizer,
                                          @NotNull HashSink hash) {
    if (!REBUILD_ON_DEPENDENCY_CHANGE) {
      hash.putInt(0);
      return;
    }

    JpsModule module = getModule();
    JpsJavaDependenciesEnumerator enumerator = JpsJavaExtensionService.dependencies(module).compileOnly().recursivelyExportedOnly();
    if (!isTests()) {
      enumerator = enumerator.productionOnly();
    }
    if (ProjectStamps.PORTABLE_CACHES) {
      enumerator = enumerator.withoutSdk();
    }

    Collection<Path> roots = enumerator.classes().getPaths();
    for (Path file : roots) {
      String path = relativizer.toRelative(file.toAbsolutePath().normalize().toString());
      getContentHash(file, hash);
      if (logBuilder != null) {
        logBuilder.append(path);
        // not a content hash, but the current hash value
        if (hash instanceof HashStream64) {
          logBuilder.append(": ").append((((HashStream64)hash).getAsLong()));
        }
        logBuilder.append("\n");
      }
      normalizedPathHashCode(path, hash);
    }
    hash.putInt(roots.size());
  }

  private static void getContentHash(Path file, HashSink hash) {
    if (!ProjectStamps.TRACK_LIBRARY_CONTENT) {
      hash.putInt(0);
      return;
    }

    try {
      if (Files.isRegularFile(file) && file.getFileName().endsWith(".jar")) {
        getFileHash(file, hash);
      }
      else {
        hash.putInt(0);
      }
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
