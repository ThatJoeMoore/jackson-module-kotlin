package com.fasterxml.jackson.module.kotlin

import com.fasterxml.jackson.databind.introspect.AnnotatedMethod
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.internal.impl.descriptors.ClassDescriptor
import kotlin.reflect.jvm.internal.impl.descriptors.DeclarationDescriptor
import kotlin.reflect.jvm.internal.impl.types.KotlinType
import kotlin.reflect.jvm.internal.impl.load.java.JvmAbi
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.kotlinFunction

/**
 * Checks if the class is an inline Kotlin class.
 * @return true if it's inline
 */
internal fun Class<*>.isInlineClass(): Boolean {
    return declaredMethods.any { it.name == "box-impl" } &&
            kotlin.descriptor.isInline
}

/**
 * Get internal kotlin.reflect.jvm.internal.KClassImpl.descriptor
 */
private val KClass<*>.descriptor
    get() = this::class.java.getDeclaredMethod("getDescriptor").apply { isAccessible = true }
        .invoke(this) as ClassDescriptor

/**
 * Checks if constructor has inline class parameters.
 */
internal fun Class<*>.hasInlineClassParameters(): Boolean {
    // just like kotlin.reflect.jvm.internal.calls.InlineClassAwareCaller
    return this.kotlin.descriptor
        .unsubstitutedPrimaryConstructor?.valueParameters?.any { it.type.isInlineClassType() }
        ?: false
}

internal fun AnnotatedMethod.getInlineClassForGetter(): KClass<*>? {
    val method = this.member
    if (!method.declaringClass.hasInlineClassParameters()) {
        return null
    }
    val kc = this.declaringClass.kotlin

    val prop = kc.memberProperties.find { it.javaGetter == method }
        ?: return null

    val cl = prop.returnType.classifier
        ?: return null

    if (cl is KClass<*> && cl.descriptor.isInline) {
        return cl
    }

    return null
}

// TODO: Use JvmAbi.IMPL_SUFFIX_FOR_INLINE_CLASS_MEMBERS when available
internal fun Class<*>.getBoxMethod(): Method =
    getDeclaredMethod("box-impl", getUnboxMethod().returnType)

internal fun Class<*>.getUnboxMethod(): Method =
    getDeclaredMethod("unbox-impl")

// Source:
// https://github.com/JetBrains/kotlin/blob/master/core/descriptors/src/org/jetbrains/kotlin/resolve/inlineClassesUtils.kt
private fun DeclarationDescriptor.isInlineClass() = this is ClassDescriptor && this.isInline

private fun KotlinType.isInlineClassType(): Boolean = constructor.declarationDescriptor?.isInlineClass() ?: false
