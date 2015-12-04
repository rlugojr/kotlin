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
@file:kotlin.jvm.JvmName("ClassMapping")
@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package kotlin.jvm

import kotlin.jvm.internal.ClassBasedDeclarationContainer
import kotlin.jvm.internal.Intrinsic
import kotlin.jvm.internal.Reflection
import kotlin.reflect.KClass

/**
 * Returns a Java [Class] instance corresponding to the given [KClass] instance.
 */
@Suppress("UNCHECKED_CAST")
@Intrinsic("kotlin.KClass.java.property")
public val <T : Any> KClass<T>.java: Class<T>
    @JvmName("getJavaClass")
    get() = (this as ClassBasedDeclarationContainer).jClass as Class<T>

@Deprecated("Provided for binary compatibility", level = DeprecationLevel.HIDDEN)
public fun <T: Any> KClass<T>.getJava(): Class<T> = this.java

/**
 * Returns a [KClass] instance corresponding to the given Java [Class] instance.
 */
@Suppress("UNCHECKED_CAST")
public val <T : Any> Class<T>.kotlin: KClass<T>
    @JvmName("getKotlinClass")
    get() = Reflection.createKotlinClass(this) as KClass<T>


@Deprecated("Provided for binary compatibility", level = DeprecationLevel.HIDDEN)
public fun <T : Any> Class<T>.getKotlin(): KClass<T> = Reflection.createKotlinClass(this) as KClass<T>


/**
 * Returns the runtime Java class of this object.
 */
@Suppress("UNCHECKED_CAST")
@Intrinsic("kotlin.javaClass.property")
public val <T: Any> T.javaClass : Class<T>
    get() = (this as java.lang.Object).getClass() as Class<T>

/**
 * Checks if array can contain element of type [T].
 */
@Intrinsic("kotlin.jvm.isArrayOf")
public fun <reified T : Any> Array<*>.isArrayOf(): Boolean =
        T::class.java.isAssignableFrom(this.javaClass.componentType)

/**
 * Returns the annotation type of this annotation.
 */
@Suppress("UNCHECKED_CAST")
public fun <T : Annotation> T.annotationClass() : KClass<out T> =
        (this as java.lang.annotation.Annotation).annotationType().kotlin as KClass<out T>
