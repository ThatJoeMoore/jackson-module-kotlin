package com.fasterxml.jackson.module.kotlin

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.cfg.MapperConfig
import com.fasterxml.jackson.databind.introspect.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.util.LRUMap
import com.fasterxml.jackson.databind.util.StdConverter
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.internal.KotlinReflectionInternalError
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.kotlinFunction

private val metadataFqName = "kotlin.Metadata"

fun Class<*>.isKotlinClass(): Boolean {
    return declaredAnnotations.any { it.annotationClass.java.name == metadataFqName }
}

class KotlinModule(val reflectionCacheSize: Int = 512, val nullToEmptyCollection: Boolean = false, val nullToEmptyMap: Boolean = false) : SimpleModule(PackageVersion.VERSION) {
    companion object {
        const val serialVersionUID = 1L
    }

    override fun setupModule(context: SetupContext) {
        super.setupModule(context)

        if (!context.isEnabled(MapperFeature.USE_ANNOTATIONS)) {
            throw IllegalStateException("The Jackson Kotlin module requires USE_ANNOTATIONS to be true or it cannot function")
        }

        val cache = ReflectionCache(reflectionCacheSize)

        context.addValueInstantiators(KotlinInstantiators(cache, nullToEmptyCollection, nullToEmptyMap))

        fun addMixIn(clazz: Class<*>, mixin: Class<*>) {
            context.setMixInAnnotations(clazz, mixin)
        }

        context.insertAnnotationIntrospector(KotlinAnnotationIntrospector(context, cache, nullToEmptyCollection, nullToEmptyMap))
        context.appendAnnotationIntrospector(KotlinNamesAnnotationIntrospector(this, cache))

        // ranges
        addMixIn(IntRange::class.java, ClosedRangeMixin::class.java)
        addMixIn(CharRange::class.java, ClosedRangeMixin::class.java)
        addMixIn(LongRange::class.java, ClosedRangeMixin::class.java)
        addMixIn(ClosedRange::class.java, ClosedRangeMixin::class.java)
    }
}

internal class ReflectionCache(reflectionCacheSize: Int) {
    sealed class BooleanTriState(val value: Boolean?) {
        class True(): BooleanTriState(true)
        class False(): BooleanTriState(false)
        class Empty(): BooleanTriState(null)

        companion object {
            val TRUE = True()
            val FALSE = False()
            val EMPTY = Empty()

            fun fromBoolean(value: Boolean?): BooleanTriState {
                return when (value) {
                    null -> EMPTY
                    true -> TRUE
                    false -> FALSE
                }
            }
        }
    }

    private val javaClassToKotlin = LRUMap<Class<Any>, KClass<Any>>(reflectionCacheSize, reflectionCacheSize)
    private val javaConstructorToKotlin = LRUMap<Constructor<Any>, KFunction<Any>>(reflectionCacheSize, reflectionCacheSize)
    private val javaMethodToKotlin = LRUMap<Method, KFunction<*>>(reflectionCacheSize, reflectionCacheSize)
    private val javaConstructorIsCreatorAnnotated = LRUMap<AnnotatedConstructor, Boolean>(reflectionCacheSize, reflectionCacheSize)
    private val javaMemberIsRequired = LRUMap<AnnotatedMember, BooleanTriState?>(reflectionCacheSize, reflectionCacheSize)
    private val classIsInline = LRUMap<Class<*>, Boolean>(reflectionCacheSize, reflectionCacheSize)
    private val inlineBoxer = LRUMap<Class<*>, Method>(reflectionCacheSize, reflectionCacheSize)
    private val inlineUnboxer = LRUMap<Class<*>, Method>(reflectionCacheSize, reflectionCacheSize)

    fun kotlinFromJava(key: Class<Any>): KClass<Any> = javaClassToKotlin.get(key) ?: key.kotlin.let { javaClassToKotlin.putIfAbsent(key, it) ?: it }
    fun kotlinFromJava(key: Constructor<Any>): KFunction<Any>? = javaConstructorToKotlin.get(key) ?: key.kotlinFunction?.let { javaConstructorToKotlin.putIfAbsent(key, it) ?: it }
    fun kotlinFromJava(key: Method): KFunction<*>? = javaMethodToKotlin.get(key) ?: key.kotlinFunction?.let { javaMethodToKotlin.putIfAbsent(key, it) ?: it }
    fun checkConstructorIsCreatorAnnotated(key: AnnotatedConstructor, calc: (AnnotatedConstructor) -> Boolean): Boolean = javaConstructorIsCreatorAnnotated.get(key) ?: calc(key).let { javaConstructorIsCreatorAnnotated.putIfAbsent(key, it) ?: it }
    fun javaMemberIsRequired(key: AnnotatedMember, calc: (AnnotatedMember) -> Boolean?): Boolean? = javaMemberIsRequired.get(key)?.value ?: calc(key).let { javaMemberIsRequired.putIfAbsent(key, BooleanTriState.fromBoolean(it))?.value ?: it }
    fun classIsInline(key: Class<*>): Boolean = classIsInline.get(key) ?: key.isInlineClass().let { classIsInline.putIfAbsent(key, it) ?: it }
    fun inlineBoxer(key: Class<*>): Method = inlineBoxer.get(key) ?: key.getBoxMethod().let { inlineBoxer.putIfAbsent(key, it) ?: it }
    fun inlineUnboxer(key: Class<*>): Method = inlineUnboxer.get(key) ?: key.getUnboxMethod().let { inlineUnboxer.putIfAbsent(key, it) ?: it }
}

internal class KotlinNamesAnnotationIntrospector(val module: KotlinModule, val cache: ReflectionCache) : NopAnnotationIntrospector() {
    /*
    override public fun findNameForDeserialization(annotated: Annotated?): PropertyName? {
        // This should not do introspection here, only for explicit naming by annotations
        return null
    }
    */

    // since 2.4
    override fun findImplicitPropertyName(member: AnnotatedMember): String? {
        if (member is AnnotatedParameter) {
            return findKotlinParameterName(member)
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    override fun findCreatorAnnotation(config: MapperConfig<*>?, member: Annotated?): JsonCreator.Mode? {
        // don't add a JsonCreator to any constructor if one is declared already

        if (member is AnnotatedConstructor && !member.declaringClass.isEnum) {
            // if has parameters, is a Kotlin class, and the parameters all have parameter annotations, then pretend we have a JsonCreator
            if (member.getParameterCount() > 0 && member.getDeclaringClass().isKotlinClass()) {
                return if (cache.checkConstructorIsCreatorAnnotated(member) {
                    val kClass = cache.kotlinFromJava(member.getDeclaringClass() as Class<Any>)
                    val kConstructor = cache.kotlinFromJava(member.getAnnotated() as Constructor<Any>)

                    if (kConstructor != null) {
                        val isPrimaryConstructor = kClass.primaryConstructor == kConstructor ||
                                (kClass.primaryConstructor == null && kClass.constructors.size == 1)

                        val propertyNames = kClass.memberProperties.map { it.name }.toSet()

                        fun KFunction<*>.isPossibleSingleString(): Boolean {
                           val result = parameters.size == 1 &&
                                    parameters[0].name !in propertyNames &&
                                    parameters[0].type.javaType == String::class.java &&
                                    parameters[0].annotations.none { it.annotationClass.java == JsonProperty::class.java }
                            return result
                        }

                        fun Collection<KFunction<*>>.filterOutSingleStringCallables(): Collection<KFunction<*>> {
                            return this.filter {  !it.isPossibleSingleString() }
                        }

                        val anyConstructorHasJsonCreator = kClass.constructors.filterOutSingleStringCallables()
                            .any { it.annotations.any { it.annotationClass.java == JsonCreator::class.java }
                        }

                        val anyCompanionMethodIsJsonCreator = member.type.rawClass.kotlin.companionObject?.declaredFunctions
                            ?.filterOutSingleStringCallables()?.any {
                            it.annotations.any { it.annotationClass.java == JvmStatic::class.java } &&
                                    it.annotations.any { it.annotationClass.java == JsonCreator::class.java }
                        } ?: false

                        // TODO:  should we do this check or not?  It could cause failures if we miss another way a property could be set
                        // val requiredProperties = kClass.declaredMemberProperties.filter {!it.returnType.isMarkedNullable }.map { it.name }.toSet()
                        // val areAllRequiredParametersInConstructor = kConstructor.parameters.all { requiredProperties.contains(it.name) }

                        val areAllParametersValid = kConstructor.parameters.size == kConstructor.parameters.count { it.name != null }

                        val isSingleStringConstructor = kConstructor.isPossibleSingleString()

                        val implyCreatorAnnotation = isPrimaryConstructor
                                && !(anyConstructorHasJsonCreator || anyCompanionMethodIsJsonCreator)
                                && areAllParametersValid
                                && !isSingleStringConstructor

                        implyCreatorAnnotation
                    } else {
                        // Might be synthetic constructor because of inline class parameters
                        member.declaringClass.hasInlineClassParameters()
                    }
                }) JsonCreator.Mode.DEFAULT else null
            }
        }

        // Support for inline classes
        if (member is AnnotatedMethod && member.name == "box-impl") {
//            val staticCreator = member.declaringClass.declaredMethods
//                .any { m -> Modifier.isStatic(m.modifiers) &&  m.isAnnotationPresent(JsonCreator::class.java) }
//            val constructorCreator = member.declaringClass.constructors
//                .any { m -> m.isAnnotationPresent(JsonCreator::class.java) }
//            return if (!staticCreator && !constructorCreator) JsonCreator.Mode.DEFAULT else null
            return JsonCreator.Mode.DEFAULT
        }

        return null
    }

    override fun hasAsValue(member: Annotated): Boolean? {
        // Support for inline classes
        if (member is AnnotatedMethod && cache.classIsInline(member.declaringClass)) {
            // Inline classes have exactly one non-static field
            val field = member.declaringClass.declaredFields.single { !Modifier.isStatic(it.modifiers) }
            val getterName = "get" + field.name.capitalize()
            // Annotate the corresponding property with @JsonValue
            if (member.name == getterName) {
                // Check if anything is annotated as @JsonValue
                return !member.declaringClass.kotlin.members
                    .any { m -> m.hasAnnotation<JsonValue>() }
            }
        }
        return null
    }

    private inline fun <reified T> KCallable<*>.hasAnnotation() =
        annotations.any { it is T } || (this as? KProperty<*>)?.javaField?.isAnnotationPresent(JsonValue::class.java) ?: false

    @Suppress("UNCHECKED_CAST")
    protected fun findKotlinParameterName(param: AnnotatedParameter): String? {
        if (param.getDeclaringClass().isKotlinClass()) {
            val member = param.getOwner().getMember()
            val name = if (member is Constructor<*>) {
                val ctor = (member as Constructor<Any>)
                val ctorParmCount = ctor.parameterTypes.size
                val ktorParmCount = try { ctor.kotlinFunction?.parameters?.size ?: 0 } catch (ex: KotlinReflectionInternalError) { 0 }
                if (ktorParmCount > 0 && ktorParmCount == ctorParmCount) {
                    ctor.kotlinFunction?.parameters?.get(param.index)?.name
                } else if (ctor.kotlinFunction == null  && member.declaringClass.hasInlineClassParameters()) {
                    // Might be synthetic constructor because of inline class parameters
                    member.declaringClass.kotlin.primaryConstructor?.parameters?.get(param.index)?.name
                } else {
                    null
                }
            } else if (member is Method) {
                try {
                    val temp = member.kotlinFunction

                    val firstParamKind = temp?.parameters?.firstOrNull()?.kind
                    val idx = if (firstParamKind != KParameter.Kind.VALUE) param.index + 1 else param.index
                    val parmCount = temp?.parameters?.size ?: 0
                    if (parmCount > idx) {
                        temp?.parameters?.get(idx)?.name
                    } else {
                        null
                    }
                } catch (ex: KotlinReflectionInternalError) {
                    null
                }
            } else {
                null
            }
            return name
        }
        return null
    }

    override fun findSerializationConverter(a: Annotated): Any? {
        if (a is AnnotatedMethod) {
            val boxedType = a.getInlineClassForGetter()
            if (boxedType != null && boxedType.java != a.rawReturnType) {
                val boxer = cache.inlineBoxer(boxedType.java).apply { isAccessible = true }
                return InlineBoxer(a.rawReturnType.kotlin, boxedType, boxer)
            }
        }
        return super.findSerializationConverter(a)
    }

    override fun findDeserializationConverter(a: Annotated): Any? {
//        if (a is AnnotatedMethod) {
//            val boxedType = a.getInlineClassForGetter()
//            if (boxedType != null) {
//                val boxer = cache.inlineBoxer(boxedType.java)
//                return InlineBoxer(a.rawReturnType.kotlin, boxedType, boxer)
//            }
//        }
        return super.findDeserializationConverter(a)
    }

    override fun findDeserializationContentConverter(a: AnnotatedMember?): Any {
        return super.findDeserializationContentConverter(a)
    }

}

// class params are used to make generic inference work
class InlineBoxer(private val inlineType: KClass<out Any>, private val boxedType: KClass<out Any>, private val boxer: Method): StdConverter<Any, Any>() {
    override fun convert(value: Any): Any? {
        if (inlineType.isInstance(value)) {
            return boxer.invoke(null, value)
        }
        return value
    }
}
