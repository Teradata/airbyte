/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.destination.teradata

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.collect.ImmutableMap
import io.airbyte.cdk.db.factory.DataSourceFactory
import io.airbyte.cdk.db.jdbc.DefaultJdbcDatabase
import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.db.jdbc.JdbcUtils
import io.airbyte.cdk.integrations.base.Destination
import io.airbyte.cdk.integrations.base.IntegrationRunner
import io.airbyte.cdk.integrations.destination.StandardNameTransformer
import io.airbyte.cdk.integrations.destination.jdbc.AbstractJdbcDestination
import io.airbyte.commons.json.Jsons
import io.airbyte.integrations.destination.teradata.util.TeradataConstants
import java.io.IOException
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.sql.SQLException
import java.util.*
import java.util.regex.Pattern
import javax.sql.DataSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The TeradataDestination class is responsible for handling the connection to the Teradata database
 * as destination from Airbyte. It extends the AbstractJdbcDestination class and implements the
 * Destination interface, facilitating the configuration and management of database interactions,
 * including setting a query band.
 */
class TeradataDestination :
    AbstractJdbcDestination(
        TeradataConstants.DRIVER_CLASS,
        StandardNameTransformer(),
        TeradataSqlOperations(),
    ),
    Destination {
    /**
     * Retrieves the data source for the Teradata database connection.
     *
     * @param config The configuration settings as a JsonNode.
     * @return The DataSource object for the Teradata connection.
     */
    override fun getDataSource(config: JsonNode): DataSource {
        val jdbcConfig = this.toJdbcConfig(config)
        val dataSource =
            DataSourceFactory.create(
                jdbcConfig[JdbcUtils.USERNAME_KEY].asText(),
                if (jdbcConfig.has(JdbcUtils.PASSWORD_KEY))
                    jdbcConfig[JdbcUtils.PASSWORD_KEY].asText()
                else null,
                TeradataConstants.DRIVER_CLASS,
                jdbcConfig[JdbcUtils.JDBC_URL_KEY].asText(),
                this.getConnectionProperties(config),
            )
        // set session query band
        setQueryBand(getDatabase(dataSource))
        return dataSource
    }
    /**
     * Retrieves the JdbcDatabase instance based on the provided DataSource.
     *
     * @param dataSource The DataSource to create the JdbcDatabase from.
     * @return The JdbcDatabase instance.
     */
    override fun getDatabase(dataSource: DataSource): JdbcDatabase {
        return DefaultJdbcDatabase(dataSource)
    }
    /**
     * Sets the Teradata session query band to identify the source of SQL requests originating from
     * Airbyte.
     *
     * @param jdbcDatabase The JdbcDatabase instance for which to set the query band.
     */
    private fun setQueryBand(jdbcDatabase: JdbcDatabase) {
        val setQueryBandSql =
            TeradataConstants.QUERY_BAND_SET +
                Companion.queryBand +
                TeradataConstants.QUERY_BAND_SESSION
        try {
            jdbcDatabase.execute(setQueryBandSql)
        } catch (ex: SQLException) {
            LOGGER.error("Error occurred while setting session query band: {}", ex.message, ex)
        }
    }
    /**
     * Retrieves the default connection properties for the Teradata database based on the provided
     * configuration.
     *
     * @param config The configuration settings as a JsonNode.
     * @return A map of default connection properties.
     */
    override fun getDefaultConnectionProperties(config: JsonNode): Map<String, String> {
        val additionalParameters: MutableMap<String, String> = HashMap()
        if (
            config.has(TeradataConstants.PARAM_SSL) &&
                config[TeradataConstants.PARAM_SSL].asBoolean()
        ) {
            if (config.has(TeradataConstants.PARAM_SSL_MODE)) {
                additionalParameters.putAll(
                    obtainConnectionOptions(config[TeradataConstants.PARAM_SSL_MODE])
                )
            } else {
                additionalParameters[TeradataConstants.PARAM_SSLMODE] = TeradataConstants.REQUIRE
            }
        }
        if (config.has(TeradataConstants.QUERY_BAND_KEY)) {
            Companion.queryBand =
                handleUserQueryBandText(
                    config[TeradataConstants.QUERY_BAND_KEY].asText(),
                )
        }
        additionalParameters[TeradataConstants.ENCRYPTDATA] = TeradataConstants.ENCRYPTDATA_ON
        return additionalParameters
    }

    /**
     * Obtains additional connection options like SSL configuration.
     *
     * @param encryption The JsonNode containing SSL parameters.
     * @return A map of additional connection properties.
     */
    private fun obtainConnectionOptions(encryption: JsonNode): Map<String, String> {
        val additionalParameters: MutableMap<String, String> = HashMap()
        if (!encryption.isNull) {
            val method = encryption[TeradataConstants.PARAM_MODE].asText()
            additionalParameters[TeradataConstants.PARAM_SSLMODE] = method

            if (TeradataConstants.VERIFY_CA == method || TeradataConstants.VERIFY_FULL == method) {
                try {
                    createCertificateFile(encryption[TeradataConstants.CA_CERT_KEY].asText())
                    additionalParameters[TeradataConstants.PARAM_SSLCA] =
                        TeradataConstants.CA_CERTIFICATE
                } catch (ioe: IOException) {
                    throw RuntimeException("Failed to create certificate file", ioe)
                }
            }
        }
        return additionalParameters
    }

    /** Creates certificate file for verify-ca and verify-full ssl connection */
    @Throws(IOException::class)
    private fun createCertificateFile(fileValue: String) {
        PrintWriter(TeradataConstants.CA_CERTIFICATE, StandardCharsets.UTF_8).use { out ->
            out.print(fileValue)
        }
    }
    /**
     * Handles and validates the user-defined query band text.
     *
     * @param queryBandText The user-defined query band text.
     * @return The validated query band text, ensuring required parameters are presentin required
     * format.
     */
    private fun handleUserQueryBandText(queryBandText: String?): String {
        if (queryBandText.isNullOrBlank()) {
            return Companion.queryBand
        }
        var updatedQueryBand = StringBuilder(queryBandText)
        // checking org doesn't exist in query_band, appending 'org=teradata-internal-telem'
        // If it exists, user might have set some value of their own, so doing nothing in that case
        val orgMatcher = Pattern.compile("org\\s*=").matcher(queryBandText)
        if (!orgMatcher.find()) {
            if (queryBandText.isNotBlank() && !queryBandText.endsWith(";")) {
                updatedQueryBand.append(";")
            }
            updatedQueryBand.append(TeradataConstants.DEFAULT_QUERY_BAND_ORG)
        }

        // Ensure appname contains airbyte is present or append it if it exists with different value
        val appNameMatcher = Pattern.compile("appname\\s*=\\s*([^;]*)").matcher(updatedQueryBand)
        if (appNameMatcher.find()) {
            val appNameValue = appNameMatcher.group(1).trim { it <= ' ' }
            if (!appNameValue.lowercase(Locale.getDefault()).contains("airbyte")) {
                updatedQueryBand =
                    StringBuilder(
                        updatedQueryBand
                            .toString()
                            .replace(
                                "appname\\s*=\\s*([^;]*)".toRegex(),
                                "appname=" + appNameValue + "_airbyte",
                            ),
                    )
            }
        } else {
            if (updatedQueryBand.isNotEmpty() && !updatedQueryBand.toString().endsWith(";")) {
                updatedQueryBand.append(";")
            }
            updatedQueryBand.append(TeradataConstants.DEFAULT_QUERY_BAND_APPNAME)
        }
        return updatedQueryBand.toString()
    }
    /**
     * Converts the provided configuration into JDBC configuration settings.
     *
     * @param config The configuration settings as a JsonNode.
     * @return The converted JsonNode containing JDBC configuration.
     */
    override fun toJdbcConfig(config: JsonNode): JsonNode {
        val schema =
            Optional.ofNullable(config[JdbcUtils.SCHEMA_KEY])
                .map { obj: JsonNode -> obj.asText() }
                .orElse(TeradataConstants.DEFAULT_SCHEMA_NAME)
        val jdbcUrl = String.format("jdbc:teradata://%s/", config[JdbcUtils.HOST_KEY].asText())

        val configBuilder =
            ImmutableMap.builder<Any, Any>()
                .put(JdbcUtils.USERNAME_KEY, config[JdbcUtils.USERNAME_KEY].asText())
                .put(JdbcUtils.JDBC_URL_KEY, jdbcUrl)
                .put(JdbcUtils.SCHEMA_KEY, schema)

        if (config.has(JdbcUtils.PASSWORD_KEY)) {
            configBuilder.put(JdbcUtils.PASSWORD_KEY, config[JdbcUtils.PASSWORD_KEY].asText())
        }
        if (config.has(JdbcUtils.JDBC_URL_PARAMS_KEY)) {
            configBuilder.put(
                JdbcUtils.JDBC_URL_PARAMS_KEY,
                config[JdbcUtils.JDBC_URL_PARAMS_KEY].asText(),
            )
        }

        return Jsons.jsonNode(configBuilder.build())
    }

    val queryBand: String
        get() = Companion.queryBand

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(TeradataDestination::class.java)

        private var queryBand = TeradataConstants.DEFAULT_QUERY_BAND

        @Throws(Exception::class)
        @JvmStatic
        fun main(args: Array<String>) {
            IntegrationRunner(TeradataDestination()).run(args)
        }
    }
}
