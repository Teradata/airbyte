/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.destination.teradata

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.collect.ImmutableMap
import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.db.jdbc.JdbcUtils
import io.airbyte.cdk.integrations.base.Destination
import io.airbyte.cdk.integrations.base.IntegrationRunner
import io.airbyte.cdk.integrations.destination.StandardNameTransformer
import io.airbyte.cdk.integrations.destination.jdbc.AbstractJdbcDestination
import io.airbyte.cdk.integrations.destination.jdbc.JdbcBufferedConsumerFactory
import io.airbyte.cdk.integrations.destination.jdbc.JdbcGenerationHandler
import io.airbyte.cdk.integrations.destination.jdbc.SqlOperations
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcDestinationHandler
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcSqlGenerator
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcV1V2Migrator
import io.airbyte.commons.json.Jsons
import io.airbyte.integrations.base.destination.typing_deduping.DestinationHandler
import io.airbyte.integrations.base.destination.typing_deduping.DestinationV1V2Migrator
import io.airbyte.integrations.base.destination.typing_deduping.SqlGenerator
import io.airbyte.integrations.base.destination.typing_deduping.migrators.Migration
import io.airbyte.integrations.base.destination.typing_deduping.migrators.MinimumDestinationState
import io.airbyte.integrations.destination.teradata.typing_deduping.TeradataDestinationHandler
import io.airbyte.integrations.destination.teradata.typing_deduping.TeradataGenerationHandler
import io.airbyte.integrations.destination.teradata.typing_deduping.TeradataSqlGenerator
import io.airbyte.integrations.destination.teradata.typing_deduping.TeradataV1V2Migrator
import java.io.IOException
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.Optional
import kotlin.collections.HashMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TeradataDestination :
    AbstractJdbcDestination<MinimumDestinationState>(
        DRIVER_CLASS,
        StandardNameTransformer()
    ),
    Destination {
    override fun getDefaultConnectionProperties(config: JsonNode): Map<String, String> {
        LOGGER.info("Satish - getDefaultConnectionProperties")
        val additionalParameters: MutableMap<String, String> = HashMap()
        if (config.has(PARAM_SSL) && config[PARAM_SSL].asBoolean()) {
            LOGGER.debug("SSL Enabled")
            if (config.has(PARAM_SSL_MODE)) {
                LOGGER.debug("Selected SSL Mode : " + config[PARAM_SSL_MODE][PARAM_MODE].asText())
                additionalParameters.putAll(obtainConnectionOptions(config[PARAM_SSL_MODE]))
            } else {
                additionalParameters[PARAM_SSLMODE] =
                    REQUIRE
            }
        }
        additionalParameters[ENCRYPTDATA] =
            ENCRYPTDATA_ON
        return additionalParameters
    }

    override fun getV1V2Migrator(
        database: JdbcDatabase,
        databaseName: String
    ): DestinationV1V2Migrator = TeradataV1V2Migrator(database)

    override fun getDatabaseName(config: JsonNode): String {
        LOGGER.info("Satish  getDatabaseName Config - {}", config)
        return config[JdbcUtils.SCHEMA_KEY].asText()
    }

    override fun getSqlGenerator(config: JsonNode): JdbcSqlGenerator {
        return TeradataSqlGenerator()
    }

    override fun getSqlOperations(config: JsonNode): SqlOperations {
        return TeradataSqlOperations()
    }

    override fun getGenerationHandler(): JdbcGenerationHandler {
        return TeradataGenerationHandler()
    }

    override fun getDestinationHandler(
        config: JsonNode,
        databaseName: String,
        database: JdbcDatabase,
        rawTableSchema: String
    ): JdbcDestinationHandler<MinimumDestinationState> {
        return TeradataDestinationHandler(databaseName, database, rawTableSchema, getGenerationHandler())
    }

    override fun getMigrations(
        database: JdbcDatabase,
        databaseName: String,
        sqlGenerator: SqlGenerator,
        destinationHandler: DestinationHandler<MinimumDestinationState>
    ): List<Migration<MinimumDestinationState>> {
        return emptyList()
    }

    override val isV2Destination: Boolean
        get() = true

    private fun obtainConnectionOptions(encryption: JsonNode): Map<String, String> {
        val additionalParameters: MutableMap<String, String> = HashMap()
        if (!encryption.isNull) {
            val method = encryption[PARAM_MODE].asText()
            when (method) {
                "verify-ca", "verify-full" -> {
                    additionalParameters[PARAM_SSLMODE] =
                        method
                    try {
                        createCertificateFile(
                            CA_CERTIFICATE,
                            encryption["ssl_ca_certificate"].asText(),
                        )
                    } catch (ioe: IOException) {
                        throw RuntimeException("Failed to create certificate file")
                    }
                    additionalParameters[PARAM_SSLCA] =
                        CA_CERTIFICATE
                }

                else -> {
                    additionalParameters[PARAM_SSLMODE] =
                        method
                }
            }
        }
        return additionalParameters
    }

    override fun toJdbcConfig(config: JsonNode): JsonNode {
        val schema =
            Optional.ofNullable(config[JdbcUtils.SCHEMA_KEY]).map { obj: JsonNode -> obj.asText() }
                .orElse(DEFAULT_SCHEMA_NAME)

        val jdbcUrl = String.format(
            "jdbc:teradata://%s/",
            config[JdbcUtils.HOST_KEY].asText(),
        )

        val configBuilder = ImmutableMap.builder<Any, Any>()
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



    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(TeradataDestination::class.java)

        /**
         * Teradata JDBC driver
         */
        const val DRIVER_CLASS: String = "com.teradata.jdbc.TeraDriver"

        /**
         * Default schema name
         */
        protected const val DEFAULT_SCHEMA_NAME: String = "def_airbyte_db"
        protected const val PARAM_MODE: String = "mode"
        protected const val PARAM_SSL: String = "ssl"
        protected const val PARAM_SSL_MODE: String = "ssl_mode"
        protected const val PARAM_SSLMODE: String = "sslmode"
        protected const val PARAM_SSLCA: String = "sslca"
        protected const val REQUIRE: String = "require"

        protected const val VERIFY_CA: String = "verify-ca"

        protected const val VERIFY_FULL: String = "verify-full"

        protected const val ALLOW: String = "allow"

        protected const val CA_CERTIFICATE: String = "ca.pem"

        protected const val CA_CERT_KEY: String = "ssl_ca_certificate"

        protected const val ENCRYPTDATA: String = "ENCRYPTDATA"

        protected const val ENCRYPTDATA_ON: String = "ON"

        @Throws(Exception::class)
        @JvmStatic
        fun main(args: Array<String>) {
            IntegrationRunner(TeradataDestination()).run(args)
        }

        @Throws(IOException::class)
        private fun createCertificateFile(fileName: String, fileValue: String) {
            PrintWriter(fileName, StandardCharsets.UTF_8).use { out ->
                out.print(fileValue)
            }
        }
    }
}
