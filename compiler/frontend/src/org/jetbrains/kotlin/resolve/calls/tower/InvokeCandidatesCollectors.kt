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

import com.intellij.util.SmartList
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.tasks.createSynthesizedInvokes
import org.jetbrains.kotlin.resolve.scopes.receivers.Receiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.util.OperatorNameConventions
import java.util.*


internal abstract class AbstractInvokeCollectors<Candidate>(
        protected val functionContext: TowerContext<Candidate>,
        private val variableCollector: ScopeTowerProcessor<Candidate>
) : ScopeTowerProcessor<Candidate> {
    // todo optimize it
    private val previousActions = ArrayList<ScopeTowerProcessor<Candidate>.() -> Unit>()
    private val candidateList: MutableList<Collection<Candidate>> = ArrayList()

    private val invokeCollectorsList: MutableList<Collection<VariableInvokeCollectorScope>> = ArrayList()


    private inner class VariableInvokeCollectorScope(
            val variableCandidate: Candidate,
            val invokeCollector: ScopeTowerProcessor<Candidate> = createInvokeCollector(variableCandidate)
    ): ScopeTowerProcessor<Candidate> by invokeCollector {
        override fun getCandidatesGroups(): List<Collection<Candidate>>
                = invokeCollector.getCandidatesGroups().map { candidateGroup ->
            candidateGroup.map { functionContext.transformCandidate(variableCandidate, it) }
        }
    }

    protected abstract fun createInvokeCollector(variableCandidate: Candidate): ScopeTowerProcessor<Candidate>

    private fun findVariablesAndCreateNewInvokeCandidates() {
        for (variableCandidates in variableCollector.getCandidatesGroups()) {
            val successfulVariables = variableCandidates.filter {
                functionContext.getStatus(it).resultingApplicability.isSuccess
            }

            if (successfulVariables.isNotEmpty()) {
                val invokeCollectors = successfulVariables.map { VariableInvokeCollectorScope(it) }
                invokeCollectorsList.add(invokeCollectors)

                for (previousAction in previousActions) {
                    invokeCollectors.forEach(previousAction)
                    candidateList.addAll(invokeCollectors.collectCandidates())
                }
            }
        }
    }

    private fun Collection<VariableInvokeCollectorScope>.collectCandidates(): List<Collection<Candidate>> {
        return when (size) {
            0 -> emptyList()
            1 -> single().getCandidatesGroups()
            // overload on variables see KT-10093 Resolve depends on the order of declaration for variable with implicit invoke
            else -> listOf(this.flatMap { it.getCandidatesGroups().flatMap { it } })
        }
    }

    private fun runNewAction(action: ScopeTowerProcessor<Candidate>.() -> Unit) {
        candidateList.clear()
        previousActions.add(action)

        for(invokeCollectors in invokeCollectorsList) {
            invokeCollectors.forEach(action)
            candidateList.addAll(invokeCollectors.collectCandidates())
        }

        variableCollector.action()
        findVariablesAndCreateNewInvokeCandidates()
    }

    init { runNewAction { /* do nothing */ } }

    override fun processTowerLevel(level: ScopeTowerLevel) = runNewAction { this.processTowerLevel(level) }

    override fun processImplicitReceiver(implicitReceiver: ReceiverValue)
            = runNewAction { this.processImplicitReceiver(implicitReceiver) }

    override fun getCandidatesGroups(): List<Collection<Candidate>> = SmartList(candidateList)
}

// todo KT-9522 Allow invoke convention for synthetic property
internal class InvokeCollectorScope<Candidate>(
        functionContext: TowerContext<Candidate>,
        private val explicitReceiver: Receiver?
) : AbstractInvokeCollectors<Candidate>(
        functionContext,
        createVariableCollector(functionContext.contextForVariable(stripExplicitReceiver = false), explicitReceiver)
) {

    // todo filter by operator
    override fun createInvokeCollector(variableCandidate: Candidate): ScopeTowerProcessor<Candidate> {
        val (variableReceiver, invokeContext) = functionContext.contextForInvoke(variableCandidate, useExplicitReceiver = false)
        return ExplicitReceiverScopeTowerCandidateCollector(invokeContext, variableReceiver, ScopeTowerLevel::getFunctions)
    }
}

internal class InvokeExtensionCollectorScope<Candidate>(
        functionContext: TowerContext<Candidate>,
        private val explicitReceiver: ReceiverValue?
) : AbstractInvokeCollectors<Candidate>(
        functionContext,
        createVariableCollector(functionContext.contextForVariable(stripExplicitReceiver = true), explicitReceiver = null)
) {

    override fun createInvokeCollector(variableCandidate: Candidate): ScopeTowerProcessor<Candidate> {
        val (variableReceiver, invokeContext) = functionContext.contextForInvoke(variableCandidate, useExplicitReceiver = true)
        val invokeDescriptor = functionContext.scopeTower.getExtensionInvokeCandidateDescriptor(variableReceiver)
                               ?: return KnownResultProcessorScope(emptyList())
        return InvokeExtensionScopeTowerProcessor(invokeContext, invokeDescriptor, explicitReceiver)
    }
}

private class InvokeExtensionScopeTowerProcessor<Candidate>(
        context: TowerContext<Candidate>,
        val invokeCandidateDescriptor: CandidateWithBoundDispatchReceiver<FunctionDescriptor>,
        val explicitReceiver: ReceiverValue?
) : AbstractScopeTowerProcessor<Candidate>(context) {
    override var candidates: Collection<Candidate> = resolve(explicitReceiver)

    private fun resolve(extensionReceiver: ReceiverValue?): Collection<Candidate> {
        if (extensionReceiver == null) return emptyList()

        val candidate = context.createCandidate(invokeCandidateDescriptor, ExplicitReceiverKind.BOTH_RECEIVERS, extensionReceiver)
        return listOf(candidate)
    }

    override fun processTowerLevel(level: ScopeTowerLevel) {
        candidates = emptyList()
    }

    // todo optimize
    override fun processImplicitReceiver(implicitReceiver: ReceiverValue) {
        candidates = resolve(implicitReceiver)
    }
}

// todo debug info
private fun ScopeTower.getExtensionInvokeCandidateDescriptor(
        possibleExtensionFunctionReceiver: ReceiverValue
): CandidateWithBoundDispatchReceiver<FunctionDescriptor>? {
    if (!KotlinBuiltIns.isExactExtensionFunctionType(possibleExtensionFunctionReceiver.type)) return null

    val extFunReceiver = possibleExtensionFunctionReceiver

    return ReceiverScopeTowerLevel(this, extFunReceiver).getFunctions(OperatorNameConventions.INVOKE)
            .single().let {
        assert(it.diagnostics.isEmpty())
        val synthesizedInvoke = createSynthesizedInvokes(listOf(it.descriptor)).single()

        // here we don't add SynthesizedDescriptor diagnostic because it should has priority as member
        CandidateWithBoundDispatchReceiverImpl(extFunReceiver, synthesizedInvoke, listOf())
    }
}

// case 1.(foo())() or
internal fun <Candidate> createCallTowerCollectorsForExplicitInvoke(
        contextForInvoke: TowerContext<Candidate>,
        expressionForInvoke: ReceiverValue,
        explicitReceiver: ReceiverValue?
): ScopeTowerProcessor<Candidate> {
    val invokeExtensionCandidate = contextForInvoke.scopeTower.getExtensionInvokeCandidateDescriptor(expressionForInvoke)
    if (invokeExtensionCandidate == null && explicitReceiver != null) {
        // case 1.(foo())(), where foo() isn't extension function
        return KnownResultProcessorScope(emptyList())
    }

    val usualInvoke = ExplicitReceiverScopeTowerCandidateCollector(contextForInvoke, expressionForInvoke, ScopeTowerLevel::getFunctions) // todo operator

    if (invokeExtensionCandidate == null) {
        return usualInvoke
    }
    else {
        return CompositeScopeTowerProcessor(
                usualInvoke,
                InvokeExtensionScopeTowerProcessor(contextForInvoke, invokeExtensionCandidate, explicitReceiver = explicitReceiver)
        )
    }
}