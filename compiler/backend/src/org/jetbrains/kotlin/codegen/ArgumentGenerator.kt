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

package org.jetbrains.kotlin.codegen

import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.calls.model.DefaultValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ExpressionValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ResolvedValueArgument
import org.jetbrains.kotlin.resolve.calls.model.VarargValueArgument
import org.jetbrains.kotlin.utils.mapToIndex
import org.jetbrains.org.objectweb.asm.Type

class ArgumentAndDeclIndex(val arg: ResolvedValueArgument, val declIndex: Int)

abstract class ArgumentGenerator {
    /**
     * @return a `List` of bit masks of default arguments that should be passed as last arguments to $default method, if there were
     * any default arguments, or an empty `List` if there were none
     *
     * @see kotlin.reflect.jvm.internal.KCallableImpl.callBy
     */
    open fun generate(
            valueArgumentsByIndex: List<ResolvedValueArgument>,
            actualArgs: List<ResolvedValueArgument>,
            lazyArguments: LazyArguments,
            isConstructor: Boolean
    ): DefaultCallArgs {
        assert(valueArgumentsByIndex.size == actualArgs.size) {
            "Value arguments collection should have same size, but ${valueArgumentsByIndex.size} != ${actualArgs.size}"
        }

        val arg2Index = valueArgumentsByIndex.mapToIndex()

        val actualArgsWithDeclIndex = actualArgs.filter { it !is DefaultValueArgument }.map {
            ArgumentAndDeclIndex(it, arg2Index[it]!!)
        }.toMutableList()

        valueArgumentsByIndex.withIndex().forEach {
            if (it.value is DefaultValueArgument) {
                actualArgsWithDeclIndex.add(it.index, ArgumentAndDeclIndex(it.value, it.index))
            }
        }

        val defaultArgs = DefaultCallArgs(valueArgumentsByIndex.size)

        for (argumentWithDeclIndex in actualArgsWithDeclIndex) {
            val argument = argumentWithDeclIndex.arg
            val declIndex = argumentWithDeclIndex.declIndex
            val expression: KtExpression?
            val value = when (argument) {
                is ExpressionValueArgument -> {
                    expression = argument.valueArgument?.getArgumentExpression()
                    generateExpression(declIndex, argument)
                }
                is DefaultValueArgument -> {
                    defaultArgs.mark(declIndex)
                    expression = null
                    generateDefault(declIndex, argument)
                }
                is VarargValueArgument -> {
                    expression = null
                    generateVararg(declIndex, argument)
                }
                else -> {
                    expression = null
                    generateOther(declIndex, argument)
                }
            }
            lazyArguments.addParameter(GeneratedValueArgument(value, parameterType(declIndex), parameterDescriptor(declIndex), declIndex, expression))
        }

        defaultArgs.generateOnStackIfNeeded(lazyArguments, isConstructor)

        return defaultArgs
    }

    protected open fun generateExpression(i: Int, argument: ExpressionValueArgument): StackValue {
        throw UnsupportedOperationException("Unsupported expression value argument #$i: $argument")
    }

    protected open fun generateDefault(i: Int, argument: DefaultValueArgument): StackValue {
        val type = parameterType(i)
        if (type.sort == Type.OBJECT || type.sort == Type.ARRAY) {
            return StackValue.constant(null, type)
        }
        else {
            //TODO check equality
            return StackValue.createDefaultPrimitiveValue(type)
        }
    }

    protected abstract fun parameterType(i: Int): Type

    protected abstract fun parameterDescriptor(i: Int): ValueParameterDescriptor?

    protected open fun generateVararg(i: Int, argument: VarargValueArgument): StackValue {
        throw UnsupportedOperationException("Unsupported vararg value argument #$i: $argument")
    }

    protected open fun generateOther(i: Int, argument: ResolvedValueArgument): StackValue {
        throw UnsupportedOperationException("Unsupported value argument #$i: $argument")
    }
}
