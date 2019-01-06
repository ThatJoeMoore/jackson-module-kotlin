package com.fasterxml.jackson.module.kotlin.test

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Test
import kotlin.test.assertEquals

class StringValue constructor(s: String) {
    val other: String = s

    @JsonValue override fun toString() = "override"
}

data class StringValue2(@get:JsonIgnore val s: String) {
    @JsonValue override fun toString() = "override"
}

class TestGithub22 {
    @Test fun testJsonValueNoMatchingMemberWithConstructor() {
        val expectedJson = "\"override\""
        val expectedObj = StringValue("override")

        val actualJson = jacksonObjectMapper().writeValueAsString(StringValue("test"))
        assertEquals(expectedJson, actualJson)

        val actualObj = jacksonObjectMapper().readValue<StringValue>("\"override\"")
        assertEquals(expectedObj.other, actualObj.other)

    }

    @Test fun testJsonValue2DataClassIgnoredMemberInConstructor() {
        val expectedJson = "\"override\""
        val expectedObj = StringValue2("override")

        val actualJson = jacksonObjectMapper().writeValueAsString(StringValue2("test"))
        assertEquals(expectedJson, actualJson)

        val actualObj = jacksonObjectMapper().readValue<StringValue2>("\"override\"")
        assertEquals(expectedObj, actualObj)

    }
}