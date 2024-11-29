/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.destination.teradata

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.collect.ImmutableMap
import io.airbyte.cdk.db.jdbc.JdbcUtils
import io.airbyte.commons.json.Jsons
import io.airbyte.commons.map.MoreMaps
import io.airbyte.integrations.destination.teradata.util.TeradataConstants
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeradataDestinationTest {
    private var config: JsonNode? = null
    val destination: TeradataDestination = TeradataDestination()

    private val EXPECTED_JDBC_URL = "jdbc:teradata://localhost/"

    private val EXTRA_JDBC_PARAMS = "key1=value1&key2=value2&key3=value3"

    private val userName: String
        get() = config!![JdbcUtils.USERNAME_KEY].asText()

    private val password: String
        get() = config!![JdbcUtils.PASSWORD_KEY].asText()

    private val hostName: String
        get() = config!![JdbcUtils.HOST_KEY].asText()

    private val schemaName: String
        get() = config!![JdbcUtils.SCHEMA_KEY].asText()

    @BeforeEach
    fun setup() {
        this.config = createConfig()
    }

    private fun createConfig(): JsonNode {
        return Jsons.jsonNode(baseParameters())
    }

    private fun createConfig(sslEnable: Boolean): JsonNode {
        val jsonNode =
            if (sslEnable) {
                Jsons.jsonNode(sslBaseParameters())
            } else {
                createConfig()
            }
        return jsonNode
    }

    private fun createConfig(sslMethod: String): JsonNode {
        val additionalParameters = getAdditionalParams(sslMethod)
        return Jsons.jsonNode(MoreMaps.merge(sslBaseParameters(), additionalParameters))
    }

    private fun getAdditionalParams(sslMethod: String): Map<String, Any> {
        val additionalParameters: Map<String, Any> =
            when (sslMethod) {
                "verify-ca",
                "verify-full" -> {
                    ImmutableMap.of<String, Any>(
                        TeradataConstants.PARAM_SSL_MODE,
                        Jsons.jsonNode(
                            ImmutableMap.of(
                                TeradataConstants.PARAM_MODE,
                                sslMethod,
                                TeradataConstants.CA_CERT_KEY,
                                "dummycertificatecontent",
                            ),
                        ),
                    )
                }
                else -> {
                    ImmutableMap.of<String, Any>(
                        TeradataConstants.PARAM_SSL_MODE,
                        Jsons.jsonNode(
                            ImmutableMap.of(
                                TeradataConstants.PARAM_MODE,
                                sslMethod,
                            ),
                        ),
                    )
                }
            }
        return additionalParameters
    }

    private fun baseParameters(): Map<String, Any> {
        return ImmutableMap.builder<String, Any>()
            .put(JdbcUtils.HOST_KEY, "localhost")
            .put(JdbcUtils.SCHEMA_KEY, "db")
            .put(JdbcUtils.USERNAME_KEY, "username")
            .put(JdbcUtils.PASSWORD_KEY, "verysecure")
            .build()
    }

    private fun sslBaseParameters(): Map<String, Any> {
        return ImmutableMap.builder<String, Any>()
            .put(TeradataConstants.PARAM_SSL, "true")
            .put(JdbcUtils.HOST_KEY, hostName)
            .put(JdbcUtils.SCHEMA_KEY, schemaName)
            .put(JdbcUtils.USERNAME_KEY, userName)
            .put(JdbcUtils.PASSWORD_KEY, password)
            .build()
    }

    private fun buildConfigNoJdbcParameters(): JsonNode {
        return Jsons.jsonNode(baseParameters())
    }

    private fun buildConfigDefaultSchema(): JsonNode {
        return Jsons.jsonNode(
            ImmutableMap.of(
                JdbcUtils.HOST_KEY,
                hostName,
                JdbcUtils.USERNAME_KEY,
                userName,
                JdbcUtils.PASSWORD_KEY,
                password,
            ),
        )
    }

    private fun buildConfigWithExtraJdbcParameters(extraParam: String): JsonNode {
        return Jsons.jsonNode(
            ImmutableMap.of(
                JdbcUtils.HOST_KEY,
                hostName,
                JdbcUtils.USERNAME_KEY,
                userName,
                JdbcUtils.SCHEMA_KEY,
                schemaName,
                JdbcUtils.JDBC_URL_PARAMS_KEY,
                extraParam,
            ),
        )
    }

    @Test
    fun testJdbcUrlAndConfigNoExtraParams() {
        val jdbcConfig = destination.toJdbcConfig(buildConfigNoJdbcParameters())
        Assertions.assertEquals(EXPECTED_JDBC_URL, jdbcConfig[JdbcUtils.JDBC_URL_KEY].asText())
        Assertions.assertEquals("username", jdbcConfig[JdbcUtils.USERNAME_KEY].asText())
        Assertions.assertEquals("db", jdbcConfig[JdbcUtils.SCHEMA_KEY].asText())
        Assertions.assertEquals("verysecure", jdbcConfig[JdbcUtils.PASSWORD_KEY].asText())
    }

    @Test
    fun testJdbcUrlEmptyExtraParams() {
        val jdbcConfig = destination.toJdbcConfig(buildConfigWithExtraJdbcParameters(""))
        Assertions.assertEquals(EXPECTED_JDBC_URL, jdbcConfig[JdbcUtils.JDBC_URL_KEY].asText())
        Assertions.assertEquals("username", jdbcConfig[JdbcUtils.USERNAME_KEY].asText())
        Assertions.assertEquals("db", jdbcConfig[JdbcUtils.SCHEMA_KEY].asText())
        Assertions.assertEquals("", jdbcConfig[JdbcUtils.JDBC_URL_PARAMS_KEY].asText())
    }

    @Test
    fun testJdbcUrlExtraParams() {
        val jdbcConfig =
            destination.toJdbcConfig(buildConfigWithExtraJdbcParameters(EXTRA_JDBC_PARAMS))
        Assertions.assertEquals(EXPECTED_JDBC_URL, jdbcConfig[JdbcUtils.JDBC_URL_KEY].asText())
        Assertions.assertEquals("username", jdbcConfig[JdbcUtils.USERNAME_KEY].asText())
        Assertions.assertEquals("db", jdbcConfig[JdbcUtils.SCHEMA_KEY].asText())
        Assertions.assertEquals(
            EXTRA_JDBC_PARAMS,
            jdbcConfig[JdbcUtils.JDBC_URL_PARAMS_KEY].asText(),
        )
    }

    @Test
    fun testDefaultSchemaName() {
        val jdbcConfig = destination.toJdbcConfig(buildConfigDefaultSchema())
        Assertions.assertEquals(EXPECTED_JDBC_URL, jdbcConfig[JdbcUtils.JDBC_URL_KEY].asText())
        Assertions.assertEquals(
            TeradataConstants.DEFAULT_SCHEMA_NAME,
            jdbcConfig[JdbcUtils.SCHEMA_KEY].asText(),
        )
    }

    @Test
    fun testSSLDisable() {
        val jdbcConfig = createConfig(false)
        val properties = destination.getDefaultConnectionProperties(jdbcConfig)
        Assertions.assertNull(properties[TeradataConstants.PARAM_SSLMODE])
    }

    @Test
    fun testSSLDefaultMode() {
        val jdbcConfig = createConfig(true)
        val properties = destination.getDefaultConnectionProperties(jdbcConfig)
        Assertions.assertEquals(
            TeradataConstants.REQUIRE,
            properties[TeradataConstants.PARAM_SSLMODE].toString(),
        )
    }

    @Test
    fun testSSLAllowMode() {
        val jdbcConfig = createConfig(TeradataConstants.ALLOW)
        val properties = destination.getDefaultConnectionProperties(jdbcConfig)
        Assertions.assertEquals(
            TeradataConstants.ALLOW,
            properties[TeradataConstants.PARAM_SSLMODE].toString(),
        )
    }

    @Test
    fun testSSLVerfifyCAMode() {
        val jdbcConfig = createConfig(TeradataConstants.VERIFY_CA)
        val properties = destination.getDefaultConnectionProperties(jdbcConfig)
        Assertions.assertEquals(
            TeradataConstants.VERIFY_CA,
            properties[TeradataConstants.PARAM_SSLMODE].toString(),
        )
        Assertions.assertNotNull(properties[TeradataConstants.PARAM_SSLCA].toString())
    }

    @Test
    fun testSSLVerfifyFullMode() {
        val jdbcConfig = createConfig(TeradataConstants.VERIFY_FULL)
        val properties = destination.getDefaultConnectionProperties(jdbcConfig)
        Assertions.assertEquals(
            TeradataConstants.VERIFY_FULL,
            properties[TeradataConstants.PARAM_SSLMODE].toString(),
        )
        Assertions.assertNotNull(properties[TeradataConstants.PARAM_SSLCA].toString())
    }

    private fun provideQueryBandTestCases(): Stream<Arguments> {
        return Stream.of(
            // Each test case includes the input query band and the expected result
            Arguments.of("", TeradataConstants.DEFAULT_QUERY_BAND),
            Arguments.of("    ", TeradataConstants.DEFAULT_QUERY_BAND),
            Arguments.of("appname=test", "appname=test_airbyte;org=teradata-internal-telem;"),
            Arguments.of("appname=test;", "appname=test_airbyte;org=teradata-internal-telem;"),
            Arguments.of("appname=airbyte", "appname=airbyte;org=teradata-internal-telem;"),
            Arguments.of("appname=airbyte;", "appname=airbyte;org=teradata-internal-telem;"),
            Arguments.of("org=test;", "org=test;appname=airbyte;"),
            Arguments.of("org=test", "org=test;appname=airbyte;"),
            Arguments.of("org=teradata-internal-telem", TeradataConstants.DEFAULT_QUERY_BAND),
            Arguments.of("org=teradata-internal-telem;", TeradataConstants.DEFAULT_QUERY_BAND),
            Arguments.of(
                TeradataConstants.DEFAULT_QUERY_BAND,
                TeradataConstants.DEFAULT_QUERY_BAND,
            ),
            Arguments.of(
                "invalid_queryband",
                "invalid_queryband;org=teradata-internal-telem;appname=airbyte;",
            ),
            Arguments.of(
                "org=teradata-internal-telem;appname=test;",
                "org=teradata-internal-telem;appname=test_airbyte;",
            ),
            Arguments.of("org=custom;appname=custom;", "org=custom;appname=custom_airbyte;"),
            Arguments.of("org=custom;appname=custom", "org=custom;appname=custom_airbyte"),
            Arguments.of(
                "org=teradata-internal-telem;appname=airbyte",
                "org=teradata-internal-telem;appname=airbyte",
            ),
            Arguments.of(
                "org = teradata-internal-telem;appname = airbyte",
                "org = teradata-internal-telem;appname = airbyte",
            ),
        )
    }

    @ParameterizedTest
    @MethodSource("provideQueryBandTestCases")
    fun testQueryBandCustom(queryBandInput: String, expectedQueryBand: String?) {
        val baseParameters = baseParameters() // Adjust to your method
        val map_custom_QB =
            ImmutableMap.of<String, Any>(
                TeradataConstants.QUERY_BAND_KEY,
                queryBandInput,
            )

        val jdbcConfig = Jsons.jsonNode(MoreMaps.merge(map_custom_QB, baseParameters))
        destination.getDefaultConnectionProperties(jdbcConfig)

        Assertions.assertEquals(expectedQueryBand, destination.queryBand)
    }
}
