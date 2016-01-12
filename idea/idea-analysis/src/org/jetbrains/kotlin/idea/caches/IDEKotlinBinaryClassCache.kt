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

package org.jetbrains.kotlin.idea.caches

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.load.kotlin.KotlinBinaryClassCache
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryClass
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.utils.Profiler

object IDEKotlinBinaryClassCache {
    data class KotlinBinaryHeaderInfo(val classHeader: KotlinClassHeader, val classId: ClassId)
    data class KotlinBinary(val isKotlinBinary: Boolean, val timestamp: Long, val headerInfo: KotlinBinaryHeaderInfo?)

    fun createHeaderInfo(kotlinBinaryClass: KotlinJvmBinaryClass?): KotlinBinaryHeaderInfo? {
        val header = kotlinBinaryClass?.classHeader
        val classId = kotlinBinaryClass?.classId

        return if (header != null && classId != null) KotlinBinaryHeaderInfo(header, classId) else null
    }

    val KOTLIN_COMPILED_FILE_ATTRIBUTE: String = "kotlin-binary-compiled-file".apply {
        ServiceManager.getService(FileAttributeService::class.java).register(this, 1)
    }

    val KEY = Key.create<KotlinBinary>(KOTLIN_COMPILED_FILE_ATTRIBUTE)

    fun getKotlinBinaryClass(file: VirtualFile, fileContent: ByteArray? = null): KotlinJvmBinaryClass? {
//        if (HasCompiledKotlinInJar.isInNoKotlinJar(file)) {
//            return null
//        }
        val profiler = Profiler.create("").mute().setPrintAccuracy(5).start()
        var state = ""
        try {
            val cached = isKotlinBinaryFileFromCache(file)
            if (cached != null) {
                if (!cached.first.isKotlinBinary) {
                    state = cached.second + "f"
                    return null
                }
            }

            val kotlinBinaryClass = KotlinBinaryClassCache.getKotlinBinaryClass(file, fileContent)

            val isKotlinBinaryClass = kotlinBinaryClass != null

            val service = ServiceManager.getService(FileAttributeService::class.java)
            service.writeBooleanAttribute(KOTLIN_COMPILED_FILE_ATTRIBUTE, file, isKotlinBinaryClass)

            file.putUserData(KEY, KotlinBinary(isKotlinBinaryClass, file.timeStamp, createHeaderInfo(kotlinBinaryClass)))

            state = "rr" + (if (fileContent != null) "r" else "!") + if (kotlinBinaryClass != null) "t" else "f"
            return kotlinBinaryClass
        }
        finally {
            println("$state %.5fs ${file.name} ${file.hashCode()} ${file.path}".format(profiler.pause().cumulative.toFloat() / 1e9))

            profiler.end()
        }
    }

    fun getKotlinBinaryClassHeaderInfo(file: VirtualFile, fileContent: ByteArray? = null): KotlinBinaryHeaderInfo? {
        val cached = isKotlinBinaryFileFromCache(file)?.first
        if (cached != null) {
            if (!cached.isKotlinBinary) return null
            if (cached.headerInfo != null) {
                return cached.headerInfo
            }
        }

        return createHeaderInfo(getKotlinBinaryClass(file, fileContent))
    }


    fun isKotlinBinaryFile(file: VirtualFile, fileContent: ByteArray? = null): Boolean = getKotlinBinaryClassHeaderInfo(file, fileContent) != null

    fun isKotlinBinaryFileFromCache(file: VirtualFile): Pair<KotlinBinary, String>? {
        val userData = file.getUserData(KEY)
        if (userData != null && userData.timestamp == file.timeStamp) {
            return userData to "xxx"
        }

        val service = ServiceManager.getService(FileAttributeService::class.java)
        val attribute = service.readBooleanAttribute(KOTLIN_COMPILED_FILE_ATTRIBUTE, file)

        if (attribute != null) {
            val result = KotlinBinary(attribute.value, file.timeStamp, null)
            file.putUserData(KEY, result)

            return result to "aaa"
        }

        return null
    }
}