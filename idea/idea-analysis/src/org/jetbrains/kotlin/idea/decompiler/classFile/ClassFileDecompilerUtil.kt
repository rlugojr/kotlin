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

package org.jetbrains.kotlin.idea.decompiler.classFile

import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ClassFileViewProvider
import org.jetbrains.kotlin.idea.caches.FileAttributeService
import org.jetbrains.kotlin.idea.caches.JarUserDataManager
import org.jetbrains.kotlin.load.kotlin.KotlinBinaryClassCache
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryClass
import org.jetbrains.kotlin.load.kotlin.ModuleMapping
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

data class IsKotlinBinary(val isKotlinBinary: Boolean, val timestamp: Long)

val KOTLIN_COMPILED_FILE_ATTRIBUTE: String = "kotlin-compiled-file".apply {
    ServiceManager.getService(FileAttributeService::class.java).register(this, 1)
}

val KEY = Key.create<IsKotlinBinary>(KOTLIN_COMPILED_FILE_ATTRIBUTE)

/**
 * Checks if this file is a compiled Kotlin class file (not necessarily ABI-compatible with the current plugin)
 */
fun isKotlinJvmCompiledFile(file: VirtualFile): Boolean {
    if (file.extension != JavaClassFileType.INSTANCE!!.defaultExtension) {
        return false
    }

    if (HasCompiledKotlinInJar.isInNoKotlinJar(file)) {
        return false
    }

    val userData = file.getUserData(KEY)
    if (userData != null && userData.timestamp == file.timeStamp) {
        return userData.isKotlinBinary
    }

    val service = ServiceManager.getService(FileAttributeService::class.java)
    val attribute = service.readBooleanAttribute(KOTLIN_COMPILED_FILE_ATTRIBUTE, file)

    if (attribute != null) {
        file.putUserData(KEY, IsKotlinBinary(attribute.value!!, file.timeStamp))
        return attribute.value
    }

    val result = isKotlinJvmCompiledFileNoCache(file)

    service.writeBooleanAttribute(KOTLIN_COMPILED_FILE_ATTRIBUTE, file, result)
    file.putUserData(KEY, IsKotlinBinary(result, file.timeStamp))

    return result
}

@Suppress("NOTHING_TO_INLINE")
private inline fun isKotlinJvmCompiledFileNoCache(file: VirtualFile): Boolean =
        KotlinBinaryClassCache.getKotlinBinaryClass(file)?.classHeader != null

/**
 * Checks if this file is a compiled Kotlin class file ABI-compatible with the current plugin
 */
fun isKotlinWithCompatibleAbiVersion(file: VirtualFile): Boolean {
    if (!isKotlinJvmCompiledFile(file)) return false

    val header = KotlinBinaryClassCache.getKotlinBinaryClass(file)?.classHeader
    return header != null && header.isCompatibleAbiVersion
}

/**
 * Checks if this file is a compiled "internal" Kotlin class, i.e. a Kotlin class (not necessarily ABI-compatible with the current plugin)
 * which should NOT be decompiled (and, as a result, shown under the library in the Project view, be searchable via Find class, etc.)
 */
fun isKotlinInternalCompiledFile(file: VirtualFile): Boolean {
    if (!isKotlinJvmCompiledFile(file)) {
        return false
    }

    if (ClassFileViewProvider.isInnerClass(file)) {
        return true
    }
    val header = KotlinBinaryClassCache.getKotlinBinaryClass(file)?.classHeader ?: return false

    return header.kind == KotlinClassHeader.Kind.SYNTHETIC_CLASS ||
           header.kind == KotlinClassHeader.Kind.MULTIFILE_CLASS_PART ||
           header.isLocalClass || header.syntheticClassKind == "PACKAGE_PART"
}

object HasCompiledKotlinInJar : JarUserDataManager.JarBooleanPropertyCounter(HasCompiledKotlinInJar::class.simpleName!!) {
    override fun hasProperty(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        if (file.extension == ModuleMapping.MAPPING_FILE_EXT) {
            return true
        }

        return isKotlinJvmCompiledFileNoCache(file)
    }

    fun isInNoKotlinJar(file: VirtualFile): Boolean =
            JarUserDataManager.hasFileWithProperty(HasCompiledKotlinInJar, file) == false
}

fun findMultifileClassParts(file: VirtualFile, multifileClass: KotlinJvmBinaryClass): List<KotlinJvmBinaryClass> {
    val packageFqName = multifileClass.classId.packageFqName
    val partsFinder = DirectoryBasedClassFinder(file.parent!!, packageFqName)
    val partNames = multifileClass.classHeader.filePartClassNames ?: return emptyList()
    return partNames.mapNotNull {
        partsFinder.findKotlinClass(ClassId(packageFqName, Name.identifier(it.substringAfterLast('/'))))
    }
}