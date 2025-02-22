// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.wizard

import com.intellij.ide.util.projectWizard.ProjectBuilder
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.module.Module
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import java.util.function.Consumer

class RootNewProjectWizardStep(override val context: WizardContext) : NewProjectWizardStep {

  override val data: UserDataHolderBase = UserDataHolderBase()

  override val propertyGraph: PropertyGraph = PropertyGraph("New project wizard")

  override var keywords: NewProjectWizardStep.Keywords = NewProjectWizardStep.Keywords()

  override fun createModuleConfigurator(): Consumer<Module>? {
    return PROJECT_BUILDER_KEY.get(data)?.createModuleConfigurator()
  }

  companion object {
    val PROJECT_BUILDER_KEY = Key.create<ProjectBuilder>(ProjectBuilder::class.java.name)
  }
}