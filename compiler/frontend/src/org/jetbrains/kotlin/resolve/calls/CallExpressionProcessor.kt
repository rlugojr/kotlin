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

package org.jetbrains.kotlin.resolve.calls

import com.intellij.lang.ASTNode
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.QualifiedExpressionResolver
import org.jetbrains.kotlin.resolve.bindingContextUtil.recordDataFlowInfo
import org.jetbrains.kotlin.resolve.bindingContextUtil.recordScope
import org.jetbrains.kotlin.resolve.calls.context.BasicCallResolutionContext
import org.jetbrains.kotlin.resolve.calls.context.CheckArgumentTypesMode
import org.jetbrains.kotlin.resolve.calls.context.ContextDependency
import org.jetbrains.kotlin.resolve.calls.context.TemporaryTraceAndCache
import org.jetbrains.kotlin.resolve.calls.results.OverloadResolutionResults
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValue
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactory
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.calls.util.FakeCallableDescriptorForObject
import org.jetbrains.kotlin.resolve.scopes.receivers.*
import org.jetbrains.kotlin.types.ErrorUtils
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.ExpressionTypingContext
import org.jetbrains.kotlin.types.expressions.ExpressionTypingServices
import org.jetbrains.kotlin.types.expressions.KotlinTypeInfo

class CallExpressionProcessor(private val expression: KtQualifiedExpression, private val context: ExpressionTypingContext,
                              private val callResolver: CallResolver, private val qualifiedExpressionResolver: QualifiedExpressionResolver,
                              private val expressionTypingServices: ExpressionTypingServices, private val builtIns: KotlinBuiltIns) {

    private val trace = context.trace

    val elementChain = qualifiedExpressionResolver.resolveQualifierInExpressionAndUnroll(expression, context) {
        val temporaryForVariable = TemporaryTraceAndCache.create(context, "trace to resolve as local variable or property", it)
        val call = CallMaker.makePropertyCall(ReceiverValue.NO_RECEIVER, null, it)
        val contextForVariable = BasicCallResolutionContext.create(
                context.replaceTraceAndCache(temporaryForVariable),
                call, CheckArgumentTypesMode.CHECK_VALUE_ARGUMENTS)
        with (callResolver.resolveSimpleProperty(contextForVariable)) {
            if (isSingleResult && resultingDescriptor is FakeCallableDescriptorForObject) {
                false
            }
            else {
                resultCode != OverloadResolutionResults.Code.NAME_NOT_FOUND &&
                resultCode != OverloadResolutionResults.Code.CANDIDATES_WITH_WRONG_RECEIVER
            }
        }
    }

    private var currentContext = context.replaceExpectedType(TypeUtils.NO_EXPECTED_TYPE)
                                        .replaceContextDependency(ContextDependency.INDEPENDENT)


    private var receiverTypeInfo = trace.get(BindingContext.QUALIFIER, elementChain.first().receiver)?.let {
        KotlinTypeInfo(null, currentContext.dataFlowInfo)
    } ?: expressionTypingServices.getTypeInfo(elementChain.first().receiver, currentContext)

    var resultTypeInfo = receiverTypeInfo

    val resultType: KotlinType?
        get() = resultTypeInfo.type

    private val errorType = ErrorUtils.createErrorType("Type for " + expression.text)

    val receiverType: KotlinType
        get() = receiverTypeInfo.type ?: errorType

    private val receiverDataFlowInfo: DataFlowInfo
        get() = receiverTypeInfo.dataFlowInfo

    private var unconditionalDataFlowInfo = receiverDataFlowInfo

    fun processReceiver(element: CallExpressionElement, receiver: Receiver): ExpressionTypingContext {
        val lastStage = element.qualified === expression
        // Drop NO_EXPECTED_TYPE / INDEPENDENT at last stage
        val baseContext = if (lastStage) context else currentContext
        currentContext = baseContext.replaceDataFlowInfo(
            if (TypeUtils.isNullableType(receiverType) && !element.safe) unconditionalDataFlowInfo
            else receiverDataFlowInfo
        )

        if (receiver.exists() && receiver is ReceiverValue && element.safe) {
            // Additional "receiver != null" information
            // Should be applied if we consider a safe call
            val receiverDataFlowValue = DataFlowValueFactory.createDataFlowValue(receiver, context)
            val dataFlowInfo = currentContext.dataFlowInfo
            if (dataFlowInfo.getNullability(receiverDataFlowValue).canBeNull()) {
                currentContext = currentContext.replaceDataFlowInfo(
                        dataFlowInfo.disequate(receiverDataFlowValue, DataFlowValue.nullValue(builtIns)))
            }
            else {
                reportUnnecessarySafeCall(receiverType, element.node, receiver)
            }
        }

        return currentContext
    }

    fun processSelector(element: CallExpressionElement, selectorTypeInfo: KotlinTypeInfo) {
        var selectorType = selectorTypeInfo.type
        val selectorExpression = element.selector
        // For the next stage, if any, current stage selector is the receiver!
        receiverTypeInfo = selectorTypeInfo

        if (element.safe && selectorType != null && TypeUtils.isNullableType(receiverType)) {
            selectorType = TypeUtils.makeNullable(selectorType)
            receiverTypeInfo = receiverTypeInfo.replaceType(selectorType)
        }

        // TODO : this is suspicious: remove this code?
        if (selectorExpression != null && selectorType != null) {
            trace.recordType(selectorExpression, selectorType)
        }
        resultTypeInfo = receiverTypeInfo.replaceDataFlowInfo(unconditionalDataFlowInfo)
        // if we are in unsafe (.) call move unconditional data flow info further
        if (!element.safe) {
            unconditionalDataFlowInfo = receiverDataFlowInfo
        }
    }

    fun recordTypeInfo(element: CallExpressionElement) {
        if (trace.get(BindingContext.PROCESSED, element.qualified) == false) {
            trace.record(BindingContext.PROCESSED, element.qualified)
            trace.record(BindingContext.EXPRESSION_TYPE_INFO, element.qualified, resultTypeInfo)
            // save scope before analyze and fix debugger: see CodeFragmentAnalyzer.correctContextForExpression
            trace.recordScope(currentContext.scope, element.qualified)
            currentContext.replaceDataFlowInfo(unconditionalDataFlowInfo).recordDataFlowInfo(element.qualified)
        }
    }

    private fun reportUnnecessarySafeCall(type: KotlinType, callOperationNode: ASTNode, explicitReceiver: Receiver?) {
        if (explicitReceiver is ExpressionReceiver && explicitReceiver.expression is KtSuperExpression) {
            trace.report(Errors.UNEXPECTED_SAFE_CALL.on(callOperationNode.psi))
        }
        else {
            trace.report(Errors.UNNECESSARY_SAFE_CALL.on(callOperationNode.psi, type))
        }
    }

}