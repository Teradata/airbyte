/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.teradata.util

import java.sql.SQLException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for the JSONStruct class. */
class JSONStructTest {

    private lateinit var struct: JSONStruct
    private val json =
        """
        {
            "id":123,
            "name":"Pankaj Kumar",
        }
    """.trimIndent()

    /** Setup method to initialize objects before each test. */
    @BeforeEach
    fun setup() {
        struct = JSONStruct("JSON", arrayOf(json))
    }

    /**
     * Test the getAttributes method.
     *
     * @throws SQLException if an SQL exception occurs
     */
    @Test
    @Throws(SQLException::class)
    fun testGetAttributes() {
        assertEquals(json, struct.attributes[0])
    }

    /** Test the getAttributes method when an exception is expected. */
    @Test
    fun testGetAttributesException() {
        val exception =
            assertThrows(SQLException::class.java) {
                val inputMap = mutableMapOf<String, Class<*>>()
                struct.getAttributes(inputMap)
            }
        val expectedMessage = "getAttributes (Map) NOT SUPPORTED"
        val actualMessage = exception.message
        assertTrue(actualMessage!!.contains(expectedMessage))
    }

    /**
     * Test the getSQLTypeName method.
     *
     * @throws SQLException if an SQL exception occurs
     */
    @Test
    @Throws(SQLException::class)
    fun testGetSQLTypeName() {
        assertEquals("JSON", struct.sqlTypeName)
    }
}
