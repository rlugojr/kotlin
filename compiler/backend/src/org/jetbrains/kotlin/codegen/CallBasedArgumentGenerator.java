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

package org.jetbrains.kotlin.codegen;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.ValueArgument;
import org.jetbrains.kotlin.resolve.calls.model.*;
import org.jetbrains.org.objectweb.asm.Type;
import org.jetbrains.org.objectweb.asm.commons.InstructionAdapter;

import java.util.List;

public class CallBasedArgumentGenerator extends ArgumentGenerator {
    private final ExpressionCodegen codegen;
    private final List<ValueParameterDescriptor> valueParameters;
    private final List<Type> valueParameterTypes;

    public CallBasedArgumentGenerator(
            @NotNull ExpressionCodegen codegen,
            @NotNull List<ValueParameterDescriptor> valueParameters,
            @NotNull List<Type> valueParameterTypes
    ) {
        this.codegen = codegen;
        this.valueParameters = valueParameters;
        this.valueParameterTypes = valueParameterTypes;

        assert valueParameters.size() == valueParameterTypes.size() :
                "Value parameters and their types mismatch in sizes: " + valueParameters.size() + " != " + valueParameterTypes.size();
    }


    @NotNull
    @Override
    public DefaultCallArgs generate(
            @NotNull List<? extends ResolvedValueArgument> valueArgumentsByIndex,
            @NotNull List<? extends ResolvedValueArgument> valueArgs,
            @NotNull LazyArguments lazyArguments,
            boolean isConstructor
    ) {
        //TODO move out
        boolean shouldMarkLineNumbers = this.codegen.isShouldMarkLineNumbers();
        this.codegen.setShouldMarkLineNumbers(false);
        DefaultCallArgs defaultArgs = super.generate(valueArgumentsByIndex, valueArgs, lazyArguments, false);
        this.codegen.setShouldMarkLineNumbers(shouldMarkLineNumbers);
        return defaultArgs;
    }

    @NotNull
    @Override
    protected StackValue generateExpression(int i, @NotNull ExpressionValueArgument argument) {
        ValueArgument valueArgument = argument.getValueArgument();
        assert valueArgument != null;
        KtExpression argumentExpression = valueArgument.getArgumentExpression();
        assert argumentExpression != null : valueArgument.asElement().getText();
        return codegen.gen(argumentExpression);
    }

    @NotNull
    @Override
    protected Type parameterType(int i) {
        return valueParameterTypes.get(i);
    }

    @NotNull
    @Override
    protected ValueParameterDescriptor parameterDescriptor(int i) {
        return valueParameters.get(i);
    }

    @NotNull
    @Override
    protected StackValue generateVararg(int i, @NotNull final VarargValueArgument argument) {
        final ValueParameterDescriptor parameter = valueParameters.get(i);
        Type type = valueParameterTypes.get(i);
        return StackValue.operation(type, new Function1<InstructionAdapter, Unit>() {
            @Override
            public Unit invoke(InstructionAdapter adapter) {
                codegen.genVarargs(argument, parameter.getType());
                return Unit.INSTANCE;
            }
        });
    }
}
