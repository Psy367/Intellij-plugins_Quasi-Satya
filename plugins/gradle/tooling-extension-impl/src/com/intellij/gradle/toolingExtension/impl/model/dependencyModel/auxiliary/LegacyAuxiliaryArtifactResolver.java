// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.gradle.toolingExtension.impl.model.dependencyModel.auxiliary;

import com.intellij.gradle.toolingExtension.impl.model.dependencyDownloadPolicyModel.GradleDependencyDownloadPolicy;
import com.intellij.gradle.toolingExtension.impl.model.dependencyModel.DefaultModuleComponentIdentifier;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.result.ComponentArtifactsResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.component.Artifact;
import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.jvm.JvmLibrary;
import org.gradle.language.base.artifact.SourcesArtifact;
import org.gradle.language.java.artifact.JavadocArtifact;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class LegacyAuxiliaryArtifactResolver extends AuxiliaryArtifactResolver {

  private final @NotNull Map<ResolvedDependency, Set<ResolvedArtifact>> resolvedArtifacts;

  public LegacyAuxiliaryArtifactResolver(@NotNull Project project,
                                         @NotNull GradleDependencyDownloadPolicy downloadPolicy,
                                         @NotNull Map<ResolvedDependency, Set<ResolvedArtifact>> resolvedArtifacts) {
    super(project, downloadPolicy);
    this.resolvedArtifacts = resolvedArtifacts;
  }

  @Override
  public @NotNull AuxiliaryConfigurationArtifacts resolve(@NotNull Configuration configuration) {
    List<Class<? extends Artifact>> artifactTypes = getRequiredArtifactTypes();
    if (artifactTypes.isEmpty()) {
      return new AuxiliaryConfigurationArtifacts(Collections.emptyMap());
    }
    List<ComponentIdentifier> components = new ArrayList<>();
    for (Collection<ResolvedArtifact> artifacts : resolvedArtifacts.values()) {
      for (ResolvedArtifact artifact : artifacts) {
        if (artifact.getId().getComponentIdentifier() instanceof ProjectComponentIdentifier) continue;
        ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
        components.add(DefaultModuleComponentIdentifier.create(id));
      }
    }
    if (components.isEmpty()) {
      return new AuxiliaryConfigurationArtifacts(Collections.emptyMap());
    }
    Set<ComponentArtifactsResult> componentResults = getDependencyHandler(configuration)
      .createArtifactResolutionQuery()
      .forComponents(components)
      .withArtifacts(JvmLibrary.class, artifactTypes)
      .execute()
      .getResolvedComponents();
    Map<ComponentIdentifier, Map<Class<? extends Artifact>, Set<File>>> artifacts = classify(componentResults);
    return new AuxiliaryConfigurationArtifacts(artifacts);
  }

  private @NotNull List<Class<? extends Artifact>> getRequiredArtifactTypes() {
    List<Class<? extends Artifact>> artifactTypes = new ArrayList<>(2);
    if (policy.isDownloadSources()) {
      artifactTypes.add(SourcesArtifact.class);
    }
    if (policy.isDownloadJavadoc()) {
      artifactTypes.add(JavadocArtifact.class);
    }
    return artifactTypes;
  }

  private @NotNull DependencyHandler getDependencyHandler(@NotNull Configuration configuration) {
    ScriptHandler buildscript = project.getBuildscript();
    boolean isBuildScriptConfiguration = buildscript.getConfigurations().contains(configuration);
    return isBuildScriptConfiguration ? buildscript.getDependencies() : project.getDependencies();
  }

  private static @NotNull Map<ComponentIdentifier, Map<Class<? extends Artifact>, Set<File>>> classify(
    @NotNull Set<ComponentArtifactsResult> components
  ) {
    Map<ComponentIdentifier, Map<Class<? extends Artifact>, Set<File>>> result = new HashMap<>();
    for (ComponentArtifactsResult component : components) {
      Map<Class<? extends Artifact>, Set<File>> classified = getArtifactTypeMap(component);
      result.put(component.getId(), classified);
    }
    return result;
  }

  private static @NotNull Map<Class<? extends Artifact>, Set<File>> getArtifactTypeMap(
    @NotNull ComponentArtifactsResult component
  ) {
    Map<Class<? extends Artifact>, Set<File>> types = new HashMap<>(2);
    types.put(SourcesArtifact.class, getResolvedAuxiliaryArtifactFiles(component, SourcesArtifact.class));
    types.put(JavadocArtifact.class, getResolvedAuxiliaryArtifactFiles(component, JavadocArtifact.class));
    return types;
  }

  private static @NotNull Set<File> getResolvedAuxiliaryArtifactFiles(
    @NotNull ComponentArtifactsResult artifactsResult,
    @NotNull Class<? extends Artifact> artifactType
  ) {
    return artifactsResult.getArtifacts(artifactType).stream()
      .filter(ResolvedArtifactResult.class::isInstance)
      .map(ResolvedArtifactResult.class::cast)
      .map(ResolvedArtifactResult::getFile)
      .collect(Collectors.toSet());
  }
}
