/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.teradata.typing_deduping

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.airbyte.cdk.db.factory.DataSourceFactory
import io.airbyte.cdk.db.jdbc.JdbcUtils
import io.airbyte.cdk.integrations.JdbcConnector
import io.airbyte.cdk.integrations.base.AirbyteTraceMessageUtility
import io.airbyte.cdk.integrations.destination.StandardNameTransformer
import io.airbyte.cdk.integrations.standardtest.destination.typing_deduping.JdbcTypingDedupingTest
import io.airbyte.commons.json.Jsons
import io.airbyte.commons.map.MoreMaps
import io.airbyte.commons.string.Strings
import io.airbyte.commons.text.Names
import io.airbyte.integrations.base.destination.typing_deduping.StreamId.Companion.concatenateRawTableName
import io.airbyte.integrations.destination.teradata.TeradataDestination
import io.airbyte.integrations.destination.teradata.envclient.TeradataHttpClient
import io.airbyte.integrations.destination.teradata.envclient.dto.CreateEnvironmentRequest
import io.airbyte.integrations.destination.teradata.envclient.dto.EnvironmentRequest
import io.airbyte.integrations.destination.teradata.envclient.dto.EnvironmentResponse
import io.airbyte.integrations.destination.teradata.envclient.dto.GetEnvironmentRequest
import io.airbyte.integrations.destination.teradata.envclient.dto.OperationRequest
import io.airbyte.integrations.destination.teradata.envclient.exception.BaseException
import io.airbyte.integrations.destination.teradata.util.TeradataConstants
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.jooq.SQLDialect
import org.jooq.conf.ParamType
import org.jooq.impl.DSL.name
import org.junit.jupiter.api.Timeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class AbstractTeradataTypingDedupingTest : JdbcTypingDedupingTest(SQLDialect.MYSQL) {
    override val imageName = "airbyte/destination-teradata:dev"
    override val sqlGenerator = TeradataSqlGenerator()
    override val nameTransformer = StandardNameTransformer()
    override fun getBaseConfig(): ObjectNode = staticConfig.deepCopy()

    override fun getDataSource(config: JsonNode?): DataSource {
        val destination = TeradataDestination()
        val jdbcConfig = destination.toJdbcConfig(config!!)
        return DataSourceFactory.create(
            jdbcConfig[JdbcUtils.USERNAME_KEY].asText(),
            if (jdbcConfig.has(JdbcUtils.PASSWORD_KEY)) jdbcConfig[JdbcUtils.PASSWORD_KEY].asText()
            else null,
            TeradataConstants.DRIVER_CLASS,
            jdbcConfig[JdbcUtils.JDBC_URL_KEY].asText(),
            getConnectionProperties(config),
            JdbcConnector.getConnectionTimeout(
                getConnectionProperties(config),
                TeradataConstants.DRIVER_CLASS,
            ),
        )
    }

    protected fun getConnectionProperties(config: JsonNode): Map<String, String> {
        val customProperties =
            JdbcUtils.parseJdbcParameters(
                config,
                JdbcUtils.JDBC_URL_PARAMS_KEY,
            )
        val defaultProperties = getDefaultConnectionProperties(config)
        assertCustomParametersDontOverwriteDefaultParameters(customProperties, defaultProperties)
        return MoreMaps.merge(customProperties, defaultProperties)
    }

    private fun assertCustomParametersDontOverwriteDefaultParameters(
        customParameters: Map<String, String>,
        defaultParameters: Map<String, String>
    ) {
        for (key in defaultParameters.keys) {
            require(
                !(customParameters.containsKey(key) &&
                    customParameters[key] != defaultParameters[key]),
            ) {
                "Cannot overwrite default JDBC parameter $key"
            }
        }
    }

    protected fun getDefaultConnectionProperties(config: JsonNode): Map<String, String> {
        val destination = TeradataDestination()
        return destination.getDefaultConnectionProperties(config)
    }

    override fun getDefaultSchema(config: JsonNode): String {
        return config["database"].asText()
    }

    override fun setDefaultSchema(config: JsonNode, schema: String?) {
        (config as ObjectNode).put("database", schema)
    }

    @Throws(Exception::class)
    override fun dumpRawTableRecords(streamNamespace: String?, streamName: String): List<JsonNode> {
        var streamNamespace = streamNamespace
        if (streamNamespace == null) {
            streamNamespace = getDefaultSchema(config!!)
        }
        // Wrap in getIdentifier as a hack for weird mysql name transformer behavior
        val tableName =
            nameTransformer.getIdentifier(
                nameTransformer.convertStreamName(
                    concatenateRawTableName(
                        streamNamespace,
                        Names.toAlphanumericAndUnderscore(streamName),
                    ),
                ),
            )
        val schema = rawSchema
        return database!!.queryJsons(dslContext.selectFrom(name(schema, tableName)).sql)
    }

    @Throws(Exception::class)
    override fun teardownStreamAndNamespace(streamNamespace: String?, streamName: String) {
        var streamNamespace = streamNamespace
        if (streamNamespace == null) {
            streamNamespace = getDefaultSchema(config!!)
        }
        database!!.execute(
            dslContext
                .dropTableIfExists(
                    name(
                        rawSchema,
                        // Wrap in getIdentifier as a hack for weird mysql name transformer behavior
                        nameTransformer.getIdentifier(
                            concatenateRawTableName(
                                streamNamespace,
                                streamName,
                            ),
                        ),
                    ),
                )
                .sql,
        )

        // mysql doesn't have schemas, it only has databases.
        // so override this method to use dropDatabase.
        database!!.execute(
            dslContext.dropDatabaseIfExists(streamNamespace).getSQL(ParamType.INLINED)
        )
    }

    @Timeout(20, unit = TimeUnit.MINUTES)
    override fun generateConfig(): JsonNode? {
        configJson =
            Jsons.clone(
                staticConfig,
            )
        val teradataHttpClient =
            TeradataHttpClient(
                configJson["env_url"].asText(),
            )
        val name = configJson["env_name"].asText()
        val token = configJson["env_token"].asText()
        val getRequest = GetEnvironmentRequest(name)
        var response: EnvironmentResponse? = null
        try {
            response = teradataHttpClient.getEnvironment(getRequest, token)
        } catch (be: BaseException) {
            LOGGER.error("Environemnt " + name + " is not available. " + be.message)
        }
        if (response == null) {
            val request =
                CreateEnvironmentRequest(
                    name,
                    configJson["env_region"].asText(),
                    configJson["env_password"].asText(),
                )
            response = teradataHttpClient.createEnvironment(request, token).get()
            LOGGER.info(
                "Environemnt " + configJson["env_name"].asText() + " is created successfully "
            )
        } else if (response.state == EnvironmentResponse.State.STOPPED) {
            val request = EnvironmentRequest(name, OperationRequest("start"))
            teradataHttpClient.startEnvironment(request, token)
        }
        (configJson as ObjectNode).put("host", response!!.ip)
        if (configJson.get("password") == null) {
            (configJson as ObjectNode).put("password", configJson.get("env_password").asText())
        }
        dataSource = getDataSource(config)
        val destination = TeradataDestination()
        database = destination.getDatabase(dataSource!!)
        return configJson
    }

    override fun globalTeardown() {
        val deleteQuery = String.format(String.format(DELETE_DATABASE, SCHEMA_NAME))
        val dropQuery = String.format(String.format(DROP_DATABASE, SCHEMA_NAME))
        try {
            database!!.execute(deleteQuery)
            database!!.execute(dropQuery)
        } catch (e: Exception) {
            AirbyteTraceMessageUtility.emitConfigErrorTrace(
                e,
                "Database " + SCHEMA_NAME + " delete got failed.",
            )
        } finally {
            DataSourceFactory.close(dataSource)
        }
    }

    companion object {
        private val LOGGER: Logger =
            LoggerFactory.getLogger(
                AbstractTeradataTypingDedupingTest::class.java,
            )

        private lateinit var configJson: JsonNode
        @get:Throws(Exception::class)
        open val staticConfig: JsonNode
            get() = Jsons.deserialize(Files.readString(Paths.get("secrets/config.json")))

        private val SCHEMA_NAME = Strings.addRandomSuffix("acc_test", "_", 5)

        private const val CREATE_DATABASE =
            "CREATE DATABASE \"%s\" AS PERMANENT = 60e6, SPOOL = 60e6 SKEW = 10 PERCENT"

        private const val DELETE_DATABASE = "DELETE DATABASE \"%s\""

        private const val DROP_DATABASE = "DROP DATABASE \"%s\""
    }
}
