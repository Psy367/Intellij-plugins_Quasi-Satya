// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.search

import com.intellij.openapi.module.Module
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.psi.psiUtil.isExpectDeclaration
import org.jetbrains.kotlin.idea.search.ideaExtensions.KotlinReferencesSearchOptions
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.hasActualModifier

object ExpectActualUtils {
    fun KtDeclaration.expectedDeclarationIfAny(): KtDeclaration? =
        ExpectActualSupport.getInstance(project).expectedDeclarationIfAny(this)

    fun KtDeclaration.actualsForExpected(module: Module? = null): Set<KtDeclaration> =
        ExpectActualSupport.getInstance(project).actualsForExpected(this, module)


    fun liftToExpected(declaration: KtDeclaration): KtDeclaration? {
        if (declaration is KtParameter) {
            val function = declaration.ownerFunction as? KtCallableDeclaration ?: return null
            val index = function.valueParameters.indexOf(declaration)
            return (liftToExpected(function) as? KtCallableDeclaration)?.valueParameters?.getOrNull(index)
        }

        return if (declaration.isExpectDeclaration()) declaration else declaration.expectedDeclarationIfAny()
    }

    fun withExpectedActuals(classOrObject: KtDeclaration): List<KtDeclaration> {
        val expect = liftToExpected(classOrObject) ?: return listOf(classOrObject)
        val actuals = expect.actualsForExpected()
        return listOf(expect) + actuals
    }

    fun getElementToSearch(
        kotlinOptions: KotlinReferencesSearchOptions,
        unwrappedElement: PsiNamedElement
    ): PsiNamedElement = if (kotlinOptions.searchForExpectedUsages && unwrappedElement is KtDeclaration && unwrappedElement.hasActualModifier()) {
        unwrappedElement.expectedDeclarationIfAny() as? PsiNamedElement
    } else {
        null
    } ?: unwrappedElement
}