/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.lazy.resolve

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.FirDeclarationDesignation
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirPrimaryConstructor
import org.jetbrains.kotlin.fir.declarations.utils.getExplicitBackingField
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirWrappedDelegateExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirLazyBlock
import org.jetbrains.kotlin.fir.expressions.impl.FirLazyDelegatedConstructorCall
import org.jetbrains.kotlin.fir.expressions.impl.FirLazyExpression
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.scopes.kotlinScopeProvider
import org.jetbrains.kotlin.fir.visitors.FirTransformer
import org.jetbrains.kotlin.fir.visitors.transformSingle
import org.jetbrains.kotlin.psi.*

internal object FirLazyBodiesCalculator {
    fun calculateLazyBodiesInside(designation: FirDeclarationDesignation) {
        designation.declaration.transform<FirElement, PersistentList<FirDeclaration>>(
            FirLazyBodiesCalculatorTransformer,
            designation.toSequence(includeTarget = false).toList().toPersistentList()
        )
    }

    fun calculateLazyBodies(firFile: FirFile) {
        firFile.transform<FirElement, PersistentList<FirDeclaration>>(FirLazyBodiesCalculatorTransformer, persistentListOf())
    }

    private fun replaceValueParameterDefaultValues(valueParameters: List<FirValueParameter>, newValueParameters: List<FirValueParameter>) {
        require(valueParameters.size == newValueParameters.size)
        for ((valueParameter, newValueParameter) in valueParameters.zip(newValueParameters)) {
            if (newValueParameter.defaultValue != null) {
                valueParameter.replaceDefaultValue(newValueParameter.defaultValue)
            }
        }
    }

    fun calculateLazyBodiesForFunction(designation: FirDeclarationDesignation) {
        val simpleFunction = designation.declaration as FirSimpleFunction
        require(needCalculatingLazyBodyForFunction(simpleFunction))
        val newFunction = RawFirNonLocalDeclarationBuilder.buildWithFunctionSymbolRebind(
            session = simpleFunction.moduleData.session,
            scopeProvider = simpleFunction.moduleData.session.kotlinScopeProvider,
            designation = designation,
            rootNonLocalDeclaration = simpleFunction.psi as KtNamedFunction,
        ) as FirSimpleFunction
        simpleFunction.apply {
            replaceBody(newFunction.body)
            replaceContractDescription(newFunction.contractDescription)
            replaceValueParameterDefaultValues(valueParameters, newFunction.valueParameters)
        }
    }

    fun calculateLazyBodyForConstructor(designation: FirDeclarationDesignation) {
        val constructor = designation.declaration as FirConstructor
        require(constructor.psi is KtConstructor<*>)
        require(needCalculatingLazyBodyForConstructor(constructor))

        val newConstructor = RawFirNonLocalDeclarationBuilder.buildWithFunctionSymbolRebind(
            session = constructor.moduleData.session,
            scopeProvider = constructor.moduleData.session.kotlinScopeProvider,
            designation = designation,
            rootNonLocalDeclaration = constructor.psi as KtConstructor<*>,
        ) as FirConstructor

        constructor.apply {
            replaceBody(newConstructor.body)
            replaceDelegatedConstructor(newConstructor.delegatedConstructor)
            replaceValueParameterDefaultValues(valueParameters, newConstructor.valueParameters)
        }
    }

    fun calculateLazyBodyForProperty(designation: FirDeclarationDesignation) {
        val firProperty = designation.declaration as FirProperty
        if (!needCalculatingLazyBodyForProperty(firProperty)) return

        val newProperty = RawFirNonLocalDeclarationBuilder.buildWithFunctionSymbolRebind(
            session = firProperty.moduleData.session,
            scopeProvider = firProperty.moduleData.session.kotlinScopeProvider,
            designation = designation,
            rootNonLocalDeclaration = firProperty.psi as KtProperty
        ) as FirProperty

        firProperty.getter?.takeIf { it.body is FirLazyBlock }?.let { getter ->
            val newGetter = newProperty.getter!!
            getter.replaceBody(newGetter.body)
            getter.replaceContractDescription(newGetter.contractDescription)
        }

        firProperty.setter?.takeIf { it.body is FirLazyBlock }?.let { setter ->
            val newSetter = newProperty.setter!!
            setter.replaceBody(newSetter.body)
            setter.replaceContractDescription(newSetter.contractDescription)
        }

        if (firProperty.initializer is FirLazyExpression) {
            firProperty.replaceInitializer(newProperty.initializer)
        }

        firProperty.getExplicitBackingField()?.takeIf { it.initializer is FirLazyExpression }?.let { backingField ->
            val newInitializer = newProperty.getExplicitBackingField()?.initializer
            backingField.replaceInitializer(newInitializer)
        }

        val delegate = firProperty.delegate as? FirWrappedDelegateExpression
        val delegateExpression = delegate?.expression
        if (delegateExpression is FirLazyExpression) {
            val newDelegate = newProperty.delegate as? FirWrappedDelegateExpression
            check(newDelegate != null) { "Invalid replacement delegate" }
            delegate.replaceExpression(newDelegate.expression)

            val delegateProviderCall = delegate.delegateProvider as? FirFunctionCall
            val delegateProviderExplicitReceiver = delegateProviderCall?.explicitReceiver
            if (delegateProviderExplicitReceiver is FirLazyExpression) {
                val newDelegateProviderExplicitReceiver = (newDelegate.delegateProvider as? FirFunctionCall)?.explicitReceiver
                check(newDelegateProviderExplicitReceiver != null) { "Invalid replacement expression" }
                delegateProviderCall.replaceExplicitReceiver(newDelegateProviderExplicitReceiver)
            }
        }
    }

    fun calculateLazyInitializerForEnumEntry(designation: FirDeclarationDesignation) {
        val enumEntry = designation.declaration as FirEnumEntry
        require(enumEntry.initializer is FirLazyExpression)
        val newEntry = RawFirNonLocalDeclarationBuilder.buildWithFunctionSymbolRebind(
            session = enumEntry.moduleData.session,
            scopeProvider = enumEntry.moduleData.session.kotlinScopeProvider,
            designation = designation,
            rootNonLocalDeclaration = enumEntry.psi as KtEnumEntry,
        ) as FirEnumEntry
        enumEntry.apply {
            replaceInitializer(newEntry.initializer)
        }
    }

    fun calculateLazyBodyForAnonymousInitializer(designation: FirDeclarationDesignation) {
        val initializer = designation.declaration as FirAnonymousInitializer
        require(initializer.body is FirLazyBlock)
        val newInitializer = RawFirNonLocalDeclarationBuilder.buildWithFunctionSymbolRebind(
            session = initializer.moduleData.session,
            scopeProvider = initializer.moduleData.session.kotlinScopeProvider,
            designation = designation,
            rootNonLocalDeclaration = initializer.psi as KtAnonymousInitializer,
        ) as FirAnonymousInitializer
        initializer.apply {
            replaceBody(newInitializer.body)
        }
    }

    fun needCalculatingLazyBodyForConstructor(firConstructor: FirConstructor): Boolean =
        needCalculatingLazyBodyForFunction(firConstructor) || firConstructor.delegatedConstructor is FirLazyDelegatedConstructorCall

    fun needCalculatingLazyBodyForFunction(firFunction: FirFunction): Boolean =
        firFunction.body is FirLazyBlock || firFunction.valueParameters.any { it.defaultValue is FirLazyExpression }

    fun needCalculatingLazyBodyForProperty(firProperty: FirProperty): Boolean =
        firProperty.getter?.let { needCalculatingLazyBodyForFunction(it) } == true
                || firProperty.setter?.let { needCalculatingLazyBodyForFunction(it) } == true
                || firProperty.initializer is FirLazyExpression
                || (firProperty.delegate as? FirWrappedDelegateExpression)?.expression is FirLazyExpression
                || firProperty.getExplicitBackingField()?.initializer is FirLazyExpression
}

private object FirLazyBodiesCalculatorTransformer : FirTransformer<PersistentList<FirDeclaration>>() {

    override fun transformFile(file: FirFile, data: PersistentList<FirDeclaration>): FirFile {
        file.declarations.forEach {
            it.transformSingle(this, data)
        }
        return file
    }

    override fun <E : FirElement> transformElement(element: E, data: PersistentList<FirDeclaration>): E {
        if (element is FirRegularClass) {
            val newList = data.add(element)
            element.declarations.forEach {
                it.transformSingle(this, newList)
            }
            element.transformChildren(this, newList)
        }
        return element
    }

    override fun transformSimpleFunction(
        simpleFunction: FirSimpleFunction,
        data: PersistentList<FirDeclaration>
    ): FirSimpleFunction {
        if (FirLazyBodiesCalculator.needCalculatingLazyBodyForFunction(simpleFunction)) {
            val designation = FirDeclarationDesignation(data, simpleFunction)
            FirLazyBodiesCalculator.calculateLazyBodiesForFunction(designation)
        }
        return simpleFunction
    }

    override fun transformConstructor(
        constructor: FirConstructor,
        data: PersistentList<FirDeclaration>
    ): FirConstructor {
        if (FirLazyBodiesCalculator.needCalculatingLazyBodyForConstructor(constructor)) {
            val designation = FirDeclarationDesignation(data, constructor)
            FirLazyBodiesCalculator.calculateLazyBodyForConstructor(designation)
        }
        return constructor
    }

    override fun transformProperty(property: FirProperty, data: PersistentList<FirDeclaration>): FirProperty {
        if (FirLazyBodiesCalculator.needCalculatingLazyBodyForProperty(property)) {
            val designation = FirDeclarationDesignation(data, property)
            FirLazyBodiesCalculator.calculateLazyBodyForProperty(designation)
        }
        return property
    }

    override fun transformPropertyAccessor(propertyAccessor: FirPropertyAccessor, data: PersistentList<FirDeclaration>): FirStatement {
        return propertyAccessor.also { transformProperty(it.propertySymbol.fir, data) }
    }

    override fun transformEnumEntry(enumEntry: FirEnumEntry, data: PersistentList<FirDeclaration>): FirStatement {
        if (enumEntry.initializer is FirLazyExpression) {
            val designation = FirDeclarationDesignation(data, enumEntry)
            FirLazyBodiesCalculator.calculateLazyInitializerForEnumEntry(designation)
        }
        return enumEntry
    }

    override fun transformAnonymousInitializer(
        anonymousInitializer: FirAnonymousInitializer, data: PersistentList<FirDeclaration>
    ): FirAnonymousInitializer {
        if (anonymousInitializer.body is FirLazyBlock) {
            val designation = FirDeclarationDesignation(data, anonymousInitializer)
            FirLazyBodiesCalculator.calculateLazyBodyForAnonymousInitializer(designation)
        }
        return anonymousInitializer
    }
}
