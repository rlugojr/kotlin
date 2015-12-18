/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.synthetic

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.PropertyDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PropertyGetterDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PropertySetterDescriptorImpl
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.incremental.record
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.load.java.propertyNameByGetMethodName
import org.jetbrains.kotlin.load.java.typeEnhacement.enhanceSignature
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.scopes.*
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf
import org.jetbrains.kotlin.types.typeUtil.isUnit
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeFirstWord
import org.jetbrains.kotlin.utils.addIfNotNull
import java.util.*
import kotlin.properties.Delegates

interface SyntheticJavaPropertyDescriptor : PropertyDescriptor {
    val getMethod: FunctionDescriptor
    val setMethod: FunctionDescriptor?

    companion object {
        fun findByGetterOrSetter(getterOrSetter: FunctionDescriptor, syntheticScopes: SyntheticScopes): SyntheticJavaPropertyDescriptor? {
            val name = getterOrSetter.getName()
            if (name.isSpecial()) return null
            val identifier = name.getIdentifier()
            if (!identifier.startsWith("get") && !identifier.startsWith("is") && !identifier.startsWith("set")) return null // optimization

            val owner = getterOrSetter.getContainingDeclaration() as? ClassDescriptor ?: return null

            val originalGetterOrSetter = getterOrSetter.original
            return syntheticScopes.collectSyntheticExtensionProperties(listOf(owner.defaultType))
                    .filterIsInstance<SyntheticJavaPropertyDescriptor>()
                    .firstOrNull { originalGetterOrSetter == it.getMethod || originalGetterOrSetter == it.setMethod }
        }

        fun findByGetterOrSetter(getterOrSetter: FunctionDescriptor, syntheticScope: SyntheticScope)
                = findByGetterOrSetter(getterOrSetter, object : SyntheticScopes {
            override val scopes: Collection<SyntheticScope> = listOf(syntheticScope)
        })

        fun propertyNameByGetMethodName(methodName: Name): Name?
                = org.jetbrains.kotlin.load.java.propertyNameByGetMethodName(methodName)

        fun propertyNameBySetMethodName(methodName: Name, withIsPrefix: Boolean): Name?
                = org.jetbrains.kotlin.load.java.propertyNameBySetMethodName(methodName, withIsPrefix)
    }
}

class JavaSyntheticPropertiesScope(storageManager: StorageManager, private val lookupTracker: LookupTracker) : SyntheticScope {
    private val syntheticPropertyByGetter = storageManager.createMemoizedFunctionWithNullableValues<FunctionDescriptor, PropertyDescriptor> {
        syntheticPropertyByGetterNotCached(it)
    }

    private fun getSyntheticPropertyAndRecordLookups(resolutionScope: ResolutionScope, name: Name, location: LookupLocation): PropertyDescriptor? {
        if (name.isSpecial) return null

        val identifier = name.identifier
        if (identifier.isEmpty()) return null

        val firstChar = identifier[0]
        if (!firstChar.isJavaIdentifierStart() || firstChar in 'A'..'Z') return null

        val possibleGetMethods = possibleGetMethodNames(name).flatMap {
            resolutionScope.getContributedFunctions(it, location)
        }.map { it.original }.filter { it.containingDeclaration is ClassDescriptor }

        val (getMethod, property) = possibleGetMethods.map { it to syntheticPropertyByGetter(it) }.singleOrNull { it.second != null } ?: return null

        lookupTracker.record(location, getMethod.containingDeclaration, setMethodName(getMethod.name))

        return property
    }

    private fun syntheticPropertyByGetterNotCached(getMethod: FunctionDescriptor): PropertyDescriptor? {
        val propertyName = propertyNameByGetMethodName(getMethod.name)
        if (propertyName == null || !isGoodGetMethod(getMethod) || !getMethod.hasJavaNonStaticOriginInHierarchy()) return null

        val ownerClass = getMethod.containingDeclaration as ClassDescriptor

        val setMethodName = setMethodName(getMethod.name)

        // this method was enhanced
        val setMethod = ownerClass.unsubstitutedMemberScope.getContributedFunctions(setMethodName, NoLookupLocation.FROM_SYNTHETIC_SCOPE)
                .singleOrNull { isGoodSetMethod(it, getMethod) }

        //TODO: it's a temporary hack while original returns a function with platform types
        val enhancedGet = getMethod.enhanceSignature()

        return MyPropertyDescriptor.create(ownerClass, enhancedGet.original, setMethod?.original, propertyName, enhancedGet.returnType!!)
    }

    private fun isGoodGetMethod(descriptor: FunctionDescriptor): Boolean {
        if (descriptor.containingDeclaration !is ClassDescriptor) return false

        val returnType = descriptor.returnType ?: return false
        if (returnType.isUnit()) return false

        return descriptor.valueParameters.isEmpty()
               && descriptor.typeParameters.isEmpty()
               && descriptor.visibility.isVisibleOutside()
    }

    private fun isGoodSetMethod(descriptor: FunctionDescriptor, getMethod: FunctionDescriptor): Boolean {
        val propertyType = getMethod.returnType ?: return false
        val parameter = descriptor.valueParameters.singleOrNull() ?: return false
        if (!TypeUtils.equalTypes(parameter.type, propertyType)) {
            if (!propertyType.isSubtypeOf(parameter.type)) return false
            if (descriptor.findOverridden {
                val baseProperty = SyntheticJavaPropertyDescriptor.findByGetterOrSetter(it, this)
                baseProperty?.getMethod?.name == getMethod.name
            } == null) return false
        }

        return parameter.varargElementType == null
               && descriptor.typeParameters.isEmpty()
               && descriptor.returnType?.let { it.isUnit() } ?: false
               && descriptor.visibility.isVisibleOutside()
    }

    private fun FunctionDescriptor.findOverridden(condition: (FunctionDescriptor) -> Boolean): FunctionDescriptor? {
        for (descriptor in overriddenDescriptors) {
            if (condition(descriptor)) return descriptor
            descriptor.findOverridden(condition)?.let { return it }
        }
        return null
    }

    override fun getSyntheticExtensionProperties(resolutionScope: ResolutionScope, name: Name, location: LookupLocation): Collection<PropertyDescriptor> {
        return listOfNotNull(getSyntheticPropertyAndRecordLookups(resolutionScope, name, location))
    }

    override fun getSyntheticExtensionProperties(resolutionScope: ResolutionScope): Collection<PropertyDescriptor> {
        val result = ArrayList<PropertyDescriptor>()
        for (descriptor in resolutionScope.getContributedDescriptors(DescriptorKindFilter.FUNCTIONS)) {
            if (descriptor is FunctionDescriptor) {
                SyntheticJavaPropertyDescriptor.propertyNameByGetMethodName(descriptor.getName()) ?: continue
                result.addIfNotNull(syntheticPropertyByGetter(descriptor.original))
            }
        }
        return result
    }

    //TODO: reuse code with generation?

    private fun possibleGetMethodNames(propertyName: Name): List<Name> {
        val result = ArrayList<Name>(3)
        val identifier = propertyName.identifier

        if (JvmAbi.startsWithIsPrefix(identifier)) {
            result.add(propertyName)
        }

        val capitalize1 = identifier.capitalizeAsciiOnly()
        val capitalize2 = identifier.capitalizeFirstWord(asciiOnly = true)
        result.add(Name.identifier("get" + capitalize1))
        if (capitalize2 != capitalize1) {
            result.add(Name.identifier("get" + capitalize2))
        }

        return result
                .filter { SyntheticJavaPropertyDescriptor.propertyNameByGetMethodName(it) == propertyName } // don't accept "uRL" for "getURL" etc
    }

    private fun setMethodName(getMethodName: Name): Name {
        val identifier = getMethodName.identifier
        val prefix = when {
            identifier.startsWith("get") -> "get"
            identifier.startsWith("is") -> "is"
            else -> throw IllegalArgumentException()
        }
        return Name.identifier("set" + identifier.removePrefix(prefix))
    }

    override fun getSyntheticExtensionFunctions(resolutionScope: ResolutionScope, name: Name, location: LookupLocation): Collection<FunctionDescriptor> = emptyList()
    override fun getSyntheticExtensionFunctions(resolutionScope: ResolutionScope): Collection<FunctionDescriptor> = emptyList()

    private class MyPropertyDescriptor(
            containingDeclaration: DeclarationDescriptor,
            original: PropertyDescriptor?,
            annotations: Annotations,
            modality: Modality,
            visibility: Visibility,
            isVar: Boolean,
            name: Name,
            kind: CallableMemberDescriptor.Kind,
            source: SourceElement
    ) : SyntheticJavaPropertyDescriptor, PropertyDescriptorImpl(containingDeclaration, original, annotations,
                                                                modality, visibility, isVar, name, kind, source,
                                                                /* lateInit = */ false, /* isConst = */ false) {

        override var getMethod: FunctionDescriptor by Delegates.notNull()
            private set

        override var setMethod: FunctionDescriptor? = null
            private set

        companion object {
            fun create(ownerClass: ClassDescriptor, getMethod: FunctionDescriptor, setMethod: FunctionDescriptor?, name: Name, type: KotlinType): MyPropertyDescriptor {
                val visibility = syntheticExtensionVisibility(getMethod)
                val descriptor = MyPropertyDescriptor(DescriptorUtils.getContainingModule(ownerClass),
                                                      null,
                                                      Annotations.EMPTY,
                                                      Modality.FINAL,
                                                      visibility,
                                                      setMethod != null,
                                                      name,
                                                      CallableMemberDescriptor.Kind.SYNTHESIZED,
                                                      SourceElement.NO_SOURCE)
                descriptor.getMethod = getMethod
                descriptor.setMethod = setMethod

                val classTypeParams = ownerClass.typeConstructor.parameters
                val typeParameters = ArrayList<TypeParameterDescriptor>(classTypeParams.size())
                val typeSubstitutor = DescriptorSubstitutor.substituteTypeParameters(classTypeParams, TypeSubstitution.EMPTY, descriptor, typeParameters)

                val propertyType = typeSubstitutor.safeSubstitute(type, Variance.INVARIANT)
                val receiverType = typeSubstitutor.safeSubstitute(ownerClass.defaultType, Variance.INVARIANT)
                descriptor.setType(propertyType, typeParameters, null, receiverType)

                val getter = PropertyGetterDescriptorImpl(descriptor,
                                                          getMethod.annotations,
                                                          Modality.FINAL,
                                                          visibility,
                                                          false,
                                                          false,
                                                          getMethod.isExternal,
                                                          CallableMemberDescriptor.Kind.SYNTHESIZED,
                                                          null,
                                                          SourceElement.NO_SOURCE)
                getter.initialize(null)

                val setter = if (setMethod != null)
                    PropertySetterDescriptorImpl(descriptor,
                                                 setMethod.annotations,
                                                 Modality.FINAL,
                                                 syntheticExtensionVisibility(setMethod),
                                                 false,
                                                 false,
                                                 setMethod.isExternal,
                                                 CallableMemberDescriptor.Kind.SYNTHESIZED,
                                                 null,
                                                 SourceElement.NO_SOURCE)
                else
                    null
                setter?.initializeDefault()

                descriptor.initialize(getter, setter)

                return descriptor
            }
        }

        override fun createSubstitutedCopy(newOwner: DeclarationDescriptor, newModality: Modality, newVisibility: Visibility, original: PropertyDescriptor?, kind: CallableMemberDescriptor.Kind): PropertyDescriptorImpl {
            return MyPropertyDescriptor(newOwner, this, annotations, newModality, newVisibility, isVar, name, kind, source).apply {
                getMethod = this@MyPropertyDescriptor.getMethod
                setMethod = this@MyPropertyDescriptor.setMethod
            }
        }

        override fun substitute(originalSubstitutor: TypeSubstitutor): PropertyDescriptor? {
            val descriptor = super<PropertyDescriptorImpl>.substitute(originalSubstitutor) as MyPropertyDescriptor
            if (descriptor == this) return descriptor

            val classTypeParameters = (getMethod.containingDeclaration as ClassDescriptor).typeConstructor.parameters
            val substitutionMap = HashMap<TypeConstructor, TypeProjection>()
            for ((typeParameter, classTypeParameter) in typeParameters.zip(classTypeParameters)) {
                val typeProjection = originalSubstitutor.substitution[typeParameter.defaultType] ?: continue
                substitutionMap[classTypeParameter.typeConstructor] = typeProjection

            }
            val classParametersSubstitutor = TypeSubstitutor.create(substitutionMap)

            descriptor.getMethod = getMethod.substitute(classParametersSubstitutor)
            descriptor.setMethod = setMethod?.substitute(classParametersSubstitutor)
            return descriptor
        }
    }
}
