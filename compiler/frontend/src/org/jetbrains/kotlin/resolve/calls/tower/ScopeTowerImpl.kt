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

package org.jetbrains.kotlin.resolve.calls.tower

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.resolve.calls.context.ResolutionContext
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValue
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactory
import org.jetbrains.kotlin.resolve.scopes.ImportingScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ClassQualifier
import org.jetbrains.kotlin.resolve.scopes.receivers.Receiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.resolve.scopes.utils.getImplicitReceiversHierarchy
import org.jetbrains.kotlin.resolve.scopes.utils.parentsWithSelf
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.containsError
import org.jetbrains.kotlin.utils.addToStdlib.check
import java.util.*


internal class CandidateWithBoundDispatchReceiverImpl<D : CallableDescriptor>(
        override val dispatchReceiver: ReceiverValue?,
        override val descriptor: D,
        override val diagnostics: List<ResolutionDiagnostic>
) : CandidateWithBoundDispatchReceiver<D> {

    override val requiresExtensionReceiver: Boolean // todo extension function by fully qualified name
        get() = descriptor.extensionReceiverParameter != null

}

private fun DataFlowInfos.getAllPossibleTypes(receiver: ReceiverValue): Collection<KotlinType> {
    return getPossibleTypes(receiver) fastPlus receiver.type
}

internal class ScopeTowerImpl(
        resolutionContext: ResolutionContext<*>,
        private val explicitReceiver: Receiver?,
        override val location: LookupLocation
): ScopeTower {
    override val smartCastCache: DataFlowInfos = DataFlowInfosImpl(resolutionContext)
    override val lexicalScope: LexicalScope = resolutionContext.scope

    override val implicitReceivers = resolutionContext.scope.getImplicitReceiversHierarchy().
            map { it.value.check { !it.type.containsError() } }.filterNotNull()

    override val levels: Sequence<ScopeTowerLevel> = createPrototypeLevels().asSequence().map { it.asTowerLevel(this) }

    // we shouldn't calculate this before we entrance to some importing scope
    private val receiversForSyntheticExtensions: Collection<KotlinType> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (explicitReceiver != null) {
            if (explicitReceiver is ReceiverValue) {
                return@lazy smartCastCache.getAllPossibleTypes(explicitReceiver)
            }

            if (explicitReceiver is ClassQualifier) {
                explicitReceiver.companionObjectReceiver?.let {
                    return@lazy smartCastCache.getPossibleTypes(it)
                }
            }

            // explicit receiver is package or class without companion object
            emptyList()
        }
        else {
            implicitReceivers.flatMap { smartCastCache.getAllPossibleTypes(it) }
        }
    }

    private sealed class LevelPrototype {
        abstract fun asTowerLevel(resolveTower: ScopeTower): ScopeTowerLevel

        class LocalScope(val lexicalScope: LexicalScope): LevelPrototype() {
            override fun asTowerLevel(resolveTower: ScopeTower) = ScopeScopeTowerLevel(resolveTower, lexicalScope)
        }

        class Scope(val lexicalScope: LexicalScope): LevelPrototype() {
            override fun asTowerLevel(resolveTower: ScopeTower) = ScopeScopeTowerLevel(resolveTower, lexicalScope)
        }

        class Receiver(val implicitReceiver: ReceiverParameterDescriptor): LevelPrototype() {
            override fun asTowerLevel(resolveTower: ScopeTower) = ReceiverScopeTowerLevel(resolveTower, implicitReceiver.value)
        }

        class ImportingScopeLevel(val importingScope: ImportingScope, val lazyReceiversForSyntheticExtensions: () -> Collection<KotlinType>): LevelPrototype() {
            override fun asTowerLevel(resolveTower: ScopeTower) = ImportingScopeScopeTowerLevel(resolveTower, importingScope, lazyReceiversForSyntheticExtensions())
        }
    }

    private fun createPrototypeLevels(): List<LevelPrototype> {
        val result = ArrayList<LevelPrototype>()

        // locals win
        result.addAll(lexicalScope.parentsWithSelf.
                filter { it is LexicalScope && it.kind.local }.
                map { LevelPrototype.LocalScope(it as LexicalScope) })

        lexicalScope.parentsWithSelf.forEach { scope ->
            if (scope is LexicalScope) {
                if (!scope.kind.local) result.add(LevelPrototype.Scope(scope))

                scope.implicitReceiver?.let { result.add(LevelPrototype.Receiver(it)) }
            }
            else {
                result.add(LevelPrototype.ImportingScopeLevel(scope as ImportingScope, { receiversForSyntheticExtensions }))
            }
        }

        return result
    }

}

private class DataFlowInfosImpl(private val resolutionContext: ResolutionContext<*>): DataFlowInfos {
    private val dataFlowInfo = resolutionContext.dataFlowInfo
    private val smartCastInfoCache = HashMap<ReceiverValue, SmartCastInfo>()

    private fun getSmartCastInfo(receiver: ReceiverValue): SmartCastInfo
            = smartCastInfoCache.getOrPut(receiver) {
        val dataFlowValue = DataFlowValueFactory.createDataFlowValue(receiver, resolutionContext)
        SmartCastInfo(dataFlowValue, dataFlowInfo.getPossibleTypes(dataFlowValue))
    }

    override fun getDataFlowValue(receiver: ReceiverValue): DataFlowValue = getSmartCastInfo(receiver).dataFlowValue

    override fun isStableReceiver(receiver: ReceiverValue): Boolean = getSmartCastInfo(receiver).dataFlowValue.isPredictable

    // exclude receiver.type
    override fun getPossibleTypes(receiver: ReceiverValue): Set<KotlinType> = getSmartCastInfo(receiver).possibleTypes

    private data class SmartCastInfo(val dataFlowValue: DataFlowValue, val possibleTypes: Set<KotlinType>)
}
