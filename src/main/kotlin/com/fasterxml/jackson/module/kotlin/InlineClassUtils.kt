package com.fasterxml.jackson.module.kotlin

import kotlin.reflect.KClass
import kotlin.reflect.jvm.internal.impl.descriptors.ClassDescriptor
import kotlin.reflect.jvm.internal.impl.descriptors.DeclarationDescriptor
import kotlin.reflect.jvm.internal.impl.types.KotlinType

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

// Source:
// https://github.com/JetBrains/kotlin/blob/master/core/descriptors/src/org/jetbrains/kotlin/resolve/inlineClassesUtils.kt
private fun DeclarationDescriptor.isInlineClass() = this is ClassDescriptor && this.isInline

private fun KotlinType.isInlineClassType(): Boolean = constructor.declarationDescriptor?.isInlineClass() ?: false
