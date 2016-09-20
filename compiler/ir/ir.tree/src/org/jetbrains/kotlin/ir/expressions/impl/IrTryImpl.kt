/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.ir.expressions.impl

import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.ir.IrElementBase
import org.jetbrains.kotlin.ir.expressions.IrCatch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.SmartList

class IrTryImpl(startOffset: Int, endOffset: Int, type: KotlinType) :
        IrExpressionBase(startOffset, endOffset, type), IrTry {
    override lateinit var tryResult: IrExpression

    override val catches: MutableList<IrCatch> = SmartList()

    override var finallyExpression: IrExpression? = null

    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R {
        return visitor.visitTry(this, data)
    }

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        tryResult.accept(visitor, data)
        catches.forEach { it.accept(visitor, data) }
        finallyExpression?.accept(visitor, data)
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        tryResult = tryResult.transform(transformer, data)
        catches.forEachIndexed { i, irCatch ->
            catches[i] = irCatch.transform(transformer, data)
        }
    }
}

class IrCatchImpl(startOffset: Int, endOffset: Int,
                  override val parameter: VariableDescriptor,
                  override var result: IrExpression
) : IrCatch, IrElementBase(startOffset, endOffset) {
    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R {
        return visitor.visitCatch(this, data)
    }

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        result.accept(visitor, data)
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        result = result.transform(transformer, data)
    }
}