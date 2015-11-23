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

@file:JvmName("SpecialBuiltinMembers")
package org.jetbrains.kotlin.load.java

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.KotlinBuiltIns.FQ_NAMES as BUILTIN_NAMES
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.load.java.BuiltinMethodsWithSpecialGenericSignature.getSpecialSignatureInfo
import org.jetbrains.kotlin.load.java.BuiltinMethodsWithSpecialGenericSignature.sameAsBuiltinMethodWithErasedValueParameters
import org.jetbrains.kotlin.load.java.BuiltinSpecialProperties.getBuiltinSpecialPropertyGetterName
import org.jetbrains.kotlin.load.java.descriptors.JavaCallableMemberDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaClassDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.types.checker.TypeCheckingProcedure
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.addToStdlib.check

private fun FqName.child(name: String): FqName = child(Name.identifier(name))
private fun FqNameUnsafe.childSafe(name: String): FqName = child(Name.identifier(name)).toSafe()

object BuiltinSpecialProperties {
    private val PROPERTY_FQ_NAME_TO_JVM_GETTER_NAME_MAP = mapOf<FqName, Name>(
            BUILTIN_NAMES._enum.childSafe("name") to Name.identifier("name"),
            BUILTIN_NAMES._enum.childSafe("ordinal") to Name.identifier("ordinal"),
            BUILTIN_NAMES.collection.child("size") to Name.identifier("size"),
            BUILTIN_NAMES.map.child("size") to Name.identifier("size"),
            BUILTIN_NAMES.charSequence.childSafe("length") to Name.identifier("length"),
            BUILTIN_NAMES.map.child("keys") to Name.identifier("keySet"),
            BUILTIN_NAMES.map.child("values") to Name.identifier("values"),
            BUILTIN_NAMES.map.child("entries") to Name.identifier("entrySet")
    )

    private val GETTER_JVM_NAME_TO_PROPERTIES_SHORT_NAME_MAP: Map<Name, List<Name>> =
            PROPERTY_FQ_NAME_TO_JVM_GETTER_NAME_MAP.getInversedShortNamesMap()

    private val SPECIAL_FQ_NAMES = PROPERTY_FQ_NAME_TO_JVM_GETTER_NAME_MAP.keys
    internal val SPECIAL_SHORT_NAMES = SPECIAL_FQ_NAMES.map { it.shortName() }.toSet()

    fun hasBuiltinSpecialPropertyFqName(callableMemberDescriptor: CallableMemberDescriptor): Boolean {
        if (callableMemberDescriptor.name !in SPECIAL_SHORT_NAMES) return false

        return callableMemberDescriptor.hasBuiltinSpecialPropertyFqNameImpl()
    }

    private fun CallableMemberDescriptor.hasBuiltinSpecialPropertyFqNameImpl(): Boolean {
        if (SPECIAL_FQ_NAMES.containsRaw(fqNameOrNull())) return true
        if (!isFromBuiltins()) return false

        return overriddenDescriptors.any { hasBuiltinSpecialPropertyFqName(it) }
    }

    fun getPropertyNameCandidatesBySpecialGetterName(name1: Name): List<Name> =
            GETTER_JVM_NAME_TO_PROPERTIES_SHORT_NAME_MAP[name1] ?: emptyList()

    fun CallableMemberDescriptor.getBuiltinSpecialPropertyGetterName(): String? {
        assert(isFromBuiltins()) { "This method is defined only for builtin members, but $this found" }

        val descriptor = propertyIfAccessor.firstOverridden { hasBuiltinSpecialPropertyFqName(it) } ?: return null
        return PROPERTY_FQ_NAME_TO_JVM_GETTER_NAME_MAP[descriptor.fqNameSafe]?.asString()
    }
}

object BuiltinMethodsWithSpecialGenericSignature {
    private val ERASED_COLLECTION_PARAMETER_FQ_NAMES = setOf(
            BUILTIN_NAMES.collection.child("containsAll"),
            BUILTIN_NAMES.mutableCollection.child("removeAll"),
            BUILTIN_NAMES.mutableCollection.child("retainAll")
    )

    public enum class DefaultValue(val value: Any?) {
        NULL(null), INDEX(-1), FALSE(false)
    }

    private val GENERIC_PARAMETERS_METHODS_TO_DEFAULT_VALUES_MAP = mapOf(
            BUILTIN_NAMES.collection.child("contains")          to DefaultValue.FALSE,
            BUILTIN_NAMES.mutableCollection.child("remove")     to DefaultValue.FALSE,
            BUILTIN_NAMES.map.child("containsKey")              to DefaultValue.FALSE,
            BUILTIN_NAMES.map.child("containsValue")            to DefaultValue.FALSE,

            BUILTIN_NAMES.map.child("get")                      to DefaultValue.NULL,
            BUILTIN_NAMES.mutableMap.child("remove")            to DefaultValue.NULL,

            BUILTIN_NAMES.list.child("indexOf")                 to DefaultValue.INDEX,
            BUILTIN_NAMES.list.child("lastIndexOf")             to DefaultValue.INDEX
    )

    private val ERASED_VALUE_PARAMETERS_FQ_NAMES =
            GENERIC_PARAMETERS_METHODS_TO_DEFAULT_VALUES_MAP.keys + ERASED_COLLECTION_PARAMETER_FQ_NAMES

    private val ERASED_VALUE_PARAMETERS_SHORT_NAMES =
            ERASED_VALUE_PARAMETERS_FQ_NAMES.map { it.shortName() }.toSet()

    private val CallableMemberDescriptor.hasErasedValueParametersInJava: Boolean
        get() = ERASED_VALUE_PARAMETERS_FQ_NAMES.containsRaw(fqNameOrNull())

    @JvmStatic
    fun getOverriddenBuiltinFunctionWithErasedValueParametersInJava(
            functionDescriptor: FunctionDescriptor
    ): FunctionDescriptor? {
        if (!functionDescriptor.name.sameAsBuiltinMethodWithErasedValueParameters) return null
        return functionDescriptor.firstOverridden { it.hasErasedValueParametersInJava } as FunctionDescriptor?
    }

    @JvmStatic
    fun getDefaultValueForOverriddenBuiltinFunction(functionDescriptor: FunctionDescriptor): DefaultValue? {
        if (functionDescriptor.name !in ERASED_VALUE_PARAMETERS_SHORT_NAMES) return null
        return functionDescriptor.firstOverridden {
            GENERIC_PARAMETERS_METHODS_TO_DEFAULT_VALUES_MAP.keys.containsRaw(it.fqNameOrNull())
        }?.let { GENERIC_PARAMETERS_METHODS_TO_DEFAULT_VALUES_MAP[it.fqNameSafe] }
    }

    val Name.sameAsBuiltinMethodWithErasedValueParameters: Boolean
        get () = this in ERASED_VALUE_PARAMETERS_SHORT_NAMES

    enum class SpecialSignatureInfo(val signature: String?) {
        ONE_COLLECTION_PARAMETER("(Ljava/util/Collection<+Ljava/lang/Object;>;)Z"),
        GENERIC_PARAMETER(null)
    }

    fun CallableMemberDescriptor.isBuiltinWithSpecialDescriptorInJvm(): Boolean {
        if (!isFromBuiltins()) return false
        return getSpecialSignatureInfo() == SpecialSignatureInfo.GENERIC_PARAMETER || doesOverrideBuiltinWithDifferentJvmName()
    }

    @JvmStatic
    fun CallableMemberDescriptor.getSpecialSignatureInfo(): SpecialSignatureInfo? {
        val builtinFqName = firstOverridden { it is FunctionDescriptor && it.hasErasedValueParametersInJava }?.fqNameOrNull()
                ?: return null

        return when (builtinFqName) {
            in ERASED_COLLECTION_PARAMETER_FQ_NAMES -> SpecialSignatureInfo.ONE_COLLECTION_PARAMETER
            in GENERIC_PARAMETERS_METHODS_TO_DEFAULT_VALUES_MAP -> SpecialSignatureInfo.GENERIC_PARAMETER
            else -> error("Unexpected kind of special builtin: $builtinFqName")
        }
    }
}

object BuiltinMethodsWithDifferentJvmName {
    val REMOVE_AT_FQ_NAME = BUILTIN_NAMES.mutableList.child("removeAt")

    val FQ_NAMES_TO_JVM_MAP: Map<FqName, Name> = mapOf(
            BUILTIN_NAMES.number.childSafe("toByte")    to Name.identifier("byteValue"),
            BUILTIN_NAMES.number.childSafe("toShort")   to Name.identifier("shortValue"),
            BUILTIN_NAMES.number.childSafe("toInt")     to Name.identifier("intValue"),
            BUILTIN_NAMES.number.childSafe("toLong")    to Name.identifier("longValue"),
            BUILTIN_NAMES.number.childSafe("toFloat")   to Name.identifier("floatValue"),
            BUILTIN_NAMES.number.childSafe("toDouble")  to Name.identifier("doubleValue"),
            REMOVE_AT_FQ_NAME                           to Name.identifier("remove"),
            BUILTIN_NAMES.charSequence.childSafe("get") to Name.identifier("charAt")
    )

    val ORIGINAL_SHORT_NAMES: List<Name> = FQ_NAMES_TO_JVM_MAP.keySet().map { it.shortName() }

    val JVM_SHORT_NAME_TO_BUILTIN_SHORT_NAMES_MAP: Map<Name, List<Name>> = FQ_NAMES_TO_JVM_MAP.getInversedShortNamesMap()

    val Name.sameAsRenamedInJvmBuiltin: Boolean
        get() = this in ORIGINAL_SHORT_NAMES

    fun getJvmName(callableMemberDescriptor: CallableMemberDescriptor): Name? {
        return FQ_NAMES_TO_JVM_MAP[callableMemberDescriptor.fqNameOrNull() ?: return null]
    }

    fun isBuiltinFunctionWithDifferentNameInJvm(callableMemberDescriptor: CallableMemberDescriptor): Boolean {
        if (!callableMemberDescriptor.isFromBuiltins()) return false
        val fqName = callableMemberDescriptor.fqNameOrNull() ?: return false
        return callableMemberDescriptor.firstOverridden { FQ_NAMES_TO_JVM_MAP.containsKey(fqName) } != null
    }

    fun getBuiltinFunctionNamesByJvmName(name: Name): List<Name> =
            JVM_SHORT_NAME_TO_BUILTIN_SHORT_NAMES_MAP[name] ?: emptyList()


    val CallableMemberDescriptor.isRemoveAtByIndex: Boolean
        get() = name.asString() == "removeAt" && fqNameOrNull() == REMOVE_AT_FQ_NAME
}

@Suppress("UNCHECKED_CAST")
fun <T : CallableMemberDescriptor> T.getOverriddenBuiltinWithDifferentJvmName(): T? {
    if (name !in BuiltinMethodsWithDifferentJvmName.ORIGINAL_SHORT_NAMES
            && propertyIfAccessor.name !in BuiltinSpecialProperties.SPECIAL_SHORT_NAMES) return null

    return when (this) {
        is PropertyDescriptor, is PropertyAccessorDescriptor ->
            firstOverridden { BuiltinSpecialProperties.hasBuiltinSpecialPropertyFqName(it.propertyIfAccessor) } as T?
        else -> firstOverridden { BuiltinMethodsWithDifferentJvmName.isBuiltinFunctionWithDifferentNameInJvm(it) } as T?
    }
}

fun CallableMemberDescriptor.doesOverrideBuiltinWithDifferentJvmName(): Boolean = getOverriddenBuiltinWithDifferentJvmName() != null

@Suppress("UNCHECKED_CAST")
fun <T : CallableMemberDescriptor> T.getOverriddenBuiltinWithDifferentJvmDescriptor(): T? {
    getOverriddenBuiltinWithDifferentJvmName()?.let { return it }

    if (!name.sameAsBuiltinMethodWithErasedValueParameters) return null

    return firstOverridden {
        it.isFromBuiltins()
                && it.getSpecialSignatureInfo() == BuiltinMethodsWithSpecialGenericSignature.SpecialSignatureInfo.GENERIC_PARAMETER
    }?.original as T?
}

fun getJvmMethodNameIfSpecial(callableMemberDescriptor: CallableMemberDescriptor): String? {
    if (callableMemberDescriptor.propertyIfAccessor.name == DescriptorUtils.ENUM_VALUES) {
        val containingDeclaration = callableMemberDescriptor.containingDeclaration
        if (callableMemberDescriptor is PropertyAccessorDescriptor
                && containingDeclaration is ClassDescriptor
                && containingDeclaration.kind == ClassKind.ENUM_CLASS) return DescriptorUtils.ENUM_VALUES.asString()
    }

    val overriddenBuiltin = getOverriddenBuiltinThatAffectsJvmName(callableMemberDescriptor)?.propertyIfAccessor
                            ?: return null
    return when (overriddenBuiltin) {
        is PropertyDescriptor -> overriddenBuiltin.getBuiltinSpecialPropertyGetterName()
        else -> BuiltinMethodsWithDifferentJvmName.getJvmName(overriddenBuiltin)?.asString()
    }
}

private fun getOverriddenBuiltinThatAffectsJvmName(
        callableMemberDescriptor: CallableMemberDescriptor
): CallableMemberDescriptor? {
    val overriddenBuiltin = callableMemberDescriptor.getOverriddenBuiltinWithDifferentJvmName() ?: return null

    if (callableMemberDescriptor.isFromBuiltins()) return overriddenBuiltin

    return null
}

public fun ClassDescriptor.hasRealKotlinSuperClassWithOverrideOf(
        specialCallableDescriptor: CallableDescriptor
): Boolean {
    val builtinContainerDefaultType = (specialCallableDescriptor.containingDeclaration as ClassDescriptor).defaultType

    var superClassDescriptor = DescriptorUtils.getSuperClassDescriptor(this)

    while (superClassDescriptor != null) {
        if (superClassDescriptor !is JavaClassDescriptor) {
            // Kotlin class

            val doesOverrideBuiltinDeclaration =
                    TypeCheckingProcedure.findCorrespondingSupertype(superClassDescriptor.defaultType, builtinContainerDefaultType) != null

            if (doesOverrideBuiltinDeclaration) {
                val containingPackageFragment = DescriptorUtils.getParentOfType(superClassDescriptor, PackageFragmentDescriptor::class.java)
                if (superClassDescriptor.builtIns.isBuiltInPackageFragment(containingPackageFragment)) return false
                return true
            }
        }

        superClassDescriptor = DescriptorUtils.getSuperClassDescriptor(superClassDescriptor)
    }

    return false
}

// Util methods
private val CallableMemberDescriptor.isFromJava: Boolean
    get() = propertyIfAccessor is JavaCallableMemberDescriptor && propertyIfAccessor.containingDeclaration is JavaClassDescriptor

private fun CallableMemberDescriptor.isFromBuiltins(): Boolean {
    val fqName = propertyIfAccessor.fqNameOrNull() ?: return false
    return fqName.toUnsafe().startsWith(KotlinBuiltIns.BUILT_INS_PACKAGE_NAME) &&
            this.module == this.builtIns.builtInsModule
}

private val CallableMemberDescriptor.propertyIfAccessor: CallableMemberDescriptor
    get() = if (this is PropertyAccessorDescriptor) correspondingProperty else this

private fun CallableDescriptor.fqNameOrNull(): FqName? = fqNameUnsafe.check { it.isSafe }?.toSafe()

public fun CallableMemberDescriptor.firstOverridden(
        predicate: (CallableMemberDescriptor) -> Boolean
): CallableMemberDescriptor? {
    var result: CallableMemberDescriptor? = null
    return DFS.dfs(listOf(this),
        object : DFS.Neighbors<CallableMemberDescriptor> {
            override fun getNeighbors(current: CallableMemberDescriptor?): Iterable<CallableMemberDescriptor> {
                return current?.overriddenDescriptors ?: emptyList()
            }
        },
        object : DFS.AbstractNodeHandler<CallableMemberDescriptor, CallableMemberDescriptor?>() {
            override fun beforeChildren(current: CallableMemberDescriptor) = result == null
            override fun afterChildren(current: CallableMemberDescriptor) {
                if (result == null && predicate(current)) {
                    result = current
                }
            }
            override fun result(): CallableMemberDescriptor? = result
        }
    )
}

public fun CallableMemberDescriptor.isFromJavaOrBuiltins() = isFromJava || isFromBuiltins()

private fun Map<FqName, Name>.getInversedShortNamesMap(): Map<Name, List<Name>> =
        entrySet().groupBy { it.value }.mapValues { entry -> entry.value.map { it.key.shortName() } }