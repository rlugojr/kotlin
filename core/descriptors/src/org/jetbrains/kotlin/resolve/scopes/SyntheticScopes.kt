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

package org.jetbrains.kotlin.resolve.scopes

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.KotlinType


interface SyntheticScope {
    fun getSyntheticExtensionProperties(resolutionScope: ResolutionScope, name: Name, location: LookupLocation): Collection<PropertyDescriptor>
    fun getSyntheticExtensionFunctions(resolutionScope: ResolutionScope, name: Name, location: LookupLocation): Collection<FunctionDescriptor>

    fun getSyntheticExtensionProperties(resolutionScope: ResolutionScope): Collection<PropertyDescriptor>
    fun getSyntheticExtensionFunctions(resolutionScope: ResolutionScope): Collection<FunctionDescriptor>
}

interface SyntheticScopes {
    val scopes: Collection<SyntheticScope>

    object Empty : SyntheticScopes {
        override val scopes: Collection<SyntheticScope> = emptyList()
    }
}

private fun <D> SyntheticScopes.collectAll(
        receiverTypes: Collection<KotlinType>,
        collector: SyntheticScope.(ResolutionScope) -> Collection<D>
) = scopes.flatMap { scope -> receiverTypes.flatMap { scope.collector(it.memberScope) } }.toSet()

fun SyntheticScopes.collectSyntheticExtensionProperties(receiverTypes: Collection<KotlinType>, name: Name, location: LookupLocation)
        = collectAll(receiverTypes) { getSyntheticExtensionProperties(it, name, location) }

fun SyntheticScopes.collectSyntheticExtensionFunctions(receiverTypes: Collection<KotlinType>, name: Name, location: LookupLocation)
        = collectAll(receiverTypes) { getSyntheticExtensionFunctions(it, name, location) }

fun SyntheticScopes.collectSyntheticExtensionProperties(receiverTypes: Collection<KotlinType>)
        = collectAll(receiverTypes) { getSyntheticExtensionProperties(it) }

fun SyntheticScopes.collectSyntheticExtensionFunctions(receiverTypes: Collection<KotlinType>)
        = collectAll(receiverTypes) { getSyntheticExtensionFunctions(it) }