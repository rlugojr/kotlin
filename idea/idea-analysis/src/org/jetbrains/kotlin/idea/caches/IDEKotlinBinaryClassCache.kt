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
import org.jetbrains.kotlin.load.kotlin.ModuleMapping
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.name.ClassId

object IDEKotlinBinaryClassCache {
    data class KotlinBinaryHeaderInfo(val classHeader: KotlinClassHeader, val classId: ClassId)
    data class KotlinBinary(val isKotlinBinary: Boolean, val timestamp: Long, val headerInfo: KotlinBinaryHeaderInfo?)

    val attributeService = ServiceManager.getService(FileAttributeService::class.java)

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
//        val profiler = Profiler.create(file.toString()).mute().setPrintAccuracy(5).start()
        try {
            val cached = isKotlinBinaryFileFromCache(file)
            if (cached != null && !cached.isKotlinBinary) {
//                println("xxx %.5fs ${file.name} ${file.hashCode()} ${file.path}".format(profiler.pause().cumulative.toFloat() / 1e9))
                return null
            }

            val kotlinBinaryClass = getKotlinBinaryClassNoCache(file, fileContent)

            val isKotlinBinaryClass = kotlinBinaryClass != null
            attributeService.writeBooleanAttribute(KOTLIN_COMPILED_FILE_ATTRIBUTE, file, isKotlinBinaryClass)

            val headerInfo = createHeaderInfo(kotlinBinaryClass)
            if (headerInfo != null) {
                file.putUserData(KEY, KotlinBinary(isKotlinBinaryClass, file.timeStamp, headerInfo))
            }

//            println("${if (headerInfo != null) "TTT" else "FFF"} %.5fs ${file.name} ${file.hashCode()} ${file.path}".format(profiler.pause().cumulative.toFloat() / 1e9))
            return kotlinBinaryClass
        }
        finally {
//            profiler.end()
        }
    }

    fun getKotlinBinaryClassHeaderInfo(file: VirtualFile, fileContent: ByteArray? = null): KotlinBinaryHeaderInfo? {
//        val profiler = Profiler.create(file.toString()).mute().setPrintAccuracy(5).start()
        val cached = isKotlinBinaryFileFromCache(file)
        if (cached != null) {
            if (!cached.isKotlinBinary) {
//                println("nnn %.5fs ${file.name} ${file.hashCode()} ${file.path}".format(profiler.pause().cumulative.toFloat() / 1e9))
//                profiler.end()
                return null
            }
            if (cached.headerInfo != null) {
//                println("kkk %.5fs ${file.name} ${file.hashCode()} ${file.path}".format(profiler.pause().cumulative.toFloat() / 1e9))
//                profiler.end()
                return cached.headerInfo
            }
        }

//        profiler.end()

        val kotlinBinaryClass = getKotlinBinaryClass(file, fileContent)
        return createHeaderInfo(kotlinBinaryClass)
    }


    fun isKotlinBinaryFile(file: VirtualFile, fileContent: ByteArray? = null): Boolean = getKotlinBinaryClassHeaderInfo(file, fileContent) != null

    private fun getKotlinBinaryClassNoCache(file: VirtualFile, fileContent: ByteArray?): KotlinJvmBinaryClass? {
            return KotlinBinaryClassCache.getKotlinBinaryClass(file, fileContent)
    }

    private fun isKotlinBinaryFileFromCache(file: VirtualFile): KotlinBinary? {
        val userData = file.getUserData(KEY)
        if (userData != null && userData.timestamp == file.timeStamp) {
            return userData
        }

        val attribute = attributeService.readBooleanAttribute(KOTLIN_COMPILED_FILE_ATTRIBUTE, file)

        if (attribute != null) {
            val result = KotlinBinary(attribute.value, file.timeStamp, null)
            if (result.isKotlinBinary) {
                file.putUserData(KEY, result)
            }

            return result
        }

        return null
    }

    object HasCompiledKotlinInJar : JarUserDataManager.JarBooleanPropertyCounter(HasCompiledKotlinInJar::class.simpleName!!) {
        override fun hasProperty(file: VirtualFile): Boolean {
            if (file.isDirectory) return false
            if (file.extension == ModuleMapping.MAPPING_FILE_EXT) {
                return true
            }

            return getKotlinBinaryClassNoCache(file, null)?.classHeader != null
        }

        fun isInNoKotlinJar(file: VirtualFile): Boolean =
                JarUserDataManager.hasFileWithProperty(HasCompiledKotlinInJar, file) == false

        fun hasKotlinJar(file: VirtualFile): Boolean =
                JarUserDataManager.hasFileWithProperty(HasCompiledKotlinInJar, file) == true
    }
}