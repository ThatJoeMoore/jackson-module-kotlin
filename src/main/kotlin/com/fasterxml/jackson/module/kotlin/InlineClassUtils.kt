package com.fasterxml.jackson.module.kotlin

import kotlin.reflect.jvm.internal.components.RuntimeModuleData
import kotlin.reflect.jvm.internal.impl.descriptors.ClassDescriptor
import kotlin.reflect.jvm.internal.impl.descriptors.DeclarationDescriptor
import kotlin.reflect.jvm.internal.impl.types.KotlinType
import kotlin.reflect.jvm.internal.structure.classId

/**
 * Checks if the class is an inline Kotlin class.
 * @return true if it's inline
 */
internal fun Class<*>.isInlineClass(): Boolean {
    return declaredMethods.any { it.name == "box-impl" } &&
            ModuleData.getClassDescriptor(this).isInline
}

/**
 * Checks if constructor has inline class parameters.
 */
internal fun Class<*>.hasInlineClassParameters(): Boolean {
    // just like kotlin.reflect.jvm.internal.calls.InlineClassAwareCaller
    return ModuleData.getClassDescriptor(this)
        .unsubstitutedPrimaryConstructor?.valueParameters?.any { it.type.isInlineClassType() }
        ?: false
}

private object ModuleData {
    private val moduleData by lazy { RuntimeModuleData.create(this.javaClass.classLoader) }

    fun getClassDescriptor(clazz: Class<*>): ClassDescriptor {
        return moduleData.deserialization.deserializeClass(clazz.classId)!!
    }
}

// Source:
// https://github.com/JetBrains/kotlin/blob/master/core/descriptors/src/org/jetbrains/kotlin/resolve/inlineClassesUtils.kt
private fun DeclarationDescriptor.isInlineClass() = this is ClassDescriptor && this.isInline

private fun KotlinType.isInlineClassType(): Boolean = constructor.declarationDescriptor?.isInlineClass() ?: false
