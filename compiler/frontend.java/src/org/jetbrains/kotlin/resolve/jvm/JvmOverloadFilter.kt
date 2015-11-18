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

package org.jetbrains.kotlin.resolve.jvm

import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.fileClasses.NoResolveFileClassesProvider
import org.jetbrains.kotlin.fileClasses.getFileClassFqName
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.OverloadFilter
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedCallableMemberDescriptor
import java.util.*


object JvmOverloadFilter : OverloadFilter {
    override fun filterPackageMemberOverloads(overloads: Collection<CallableMemberDescriptor>): Collection<CallableMemberDescriptor> {
        val result = ArrayList<CallableMemberDescriptor>()

        val sourceClassesFQNs = HashSet<FqName>()
        for (overload in overloads) {
            val file = DescriptorToSourceUtils.getContainingFile(overload) ?: continue
            result.add(overload)
            sourceClassesFQNs.add(NoResolveFileClassesProvider.getFileClassFqName(file))
        }

        for (overload in overloads) {
            if (overload !is DeserializedCallableMemberDescriptor) continue

            val containingDeclaration = overload.containingDeclaration
            if (containingDeclaration !is PackageFragmentDescriptor) {
                throw AssertionError("Package member expected; got $overload with containing declaration $containingDeclaration")
            }
            val implClassName = JvmFileClassUtil.getImplClassName(overload) ?:
                                throw AssertionError("No implClassName: $overload")
            val implClassFQN = containingDeclaration.fqName.child(implClassName)
            if (!sourceClassesFQNs.contains(implClassFQN)) {
                result.add(overload)
            }
        }

        return result
    }
}
