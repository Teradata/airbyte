/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.destination.teradata.typing_deduping

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.airbyte.cdk.db.factory.DataSourceFactory
import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.db.jdbc.JdbcUtils
import io.airbyte.cdk.integrations.JdbcConnector
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_EXTRACTED_AT
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_ID
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_LOADED_AT
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_META
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_RAW_ID
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_DATA
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_EMITTED_AT
import io.airbyte.cdk.integrations.destination.StandardNameTransformer
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcSqlGenerator
import io.airbyte.cdk.integrations.standardtest.destination.typing_deduping.JdbcSqlGeneratorIntegrationTest
import io.airbyte.commons.json.Jsons
import io.airbyte.commons.map.MoreMaps
import io.airbyte.integrations.base.destination.typing_deduping.DestinationHandler
import io.airbyte.integrations.base.destination.typing_deduping.StreamId
import io.airbyte.integrations.base.destination.typing_deduping.migrators.MinimumDestinationState
import io.airbyte.integrations.destination.teradata.TeradataDestination
import io.airbyte.integrations.destination.teradata.envclient.TeradataHttpClient
import io.airbyte.integrations.destination.teradata.envclient.dto.CreateEnvironmentRequest
import io.airbyte.integrations.destination.teradata.envclient.dto.EnvironmentRequest
import io.airbyte.integrations.destination.teradata.envclient.dto.EnvironmentResponse
import io.airbyte.integrations.destination.teradata.envclient.dto.GetEnvironmentRequest
import io.airbyte.integrations.destination.teradata.envclient.dto.OperationRequest
import io.airbyte.integrations.destination.teradata.envclient.exception.BaseException
import io.airbyte.integrations.destination.teradata.typing_deduping.AbstractTeradataTypingDedupingTest.Companion.staticConfig
import io.airbyte.integrations.destination.teradata.util.TeradataConstants
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatterBuilder
import javax.sql.DataSource
import org.jooq.DataType
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.conf.ParamType
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TeradataSqlGeneratorIntegrationTest :
    JdbcSqlGeneratorIntegrationTest<MinimumDestinationState>() {

    override val sqlDialect: SQLDialect = SQLDialect.MYSQL
    override val sqlGenerator: JdbcSqlGenerator = TeradataSqlGenerator()
    override val structType: DataType<*> = TeradataSqlGenerator.JSON_TYPE
    override val supportsSafeCast: Boolean = false
    override val database = Companion.database
    override val destinationHandler: DestinationHandler<MinimumDestinationState>
        // lazy init. We need `namespace` to be initialized before this call.
        get() = TeradataDestinationHandler(Companion.database, namespace)

    @Throws(Exception::class)
    override fun insertRawTableRecords(streamId: StreamId, records: List<JsonNode>) {
        reformatMetaColumnTimestamps(records)
        super.insertRawTableRecords(streamId, records)
    }

    @Throws(Exception::class)
    override fun insertFinalTableRecords(
        includeCdcDeletedAt: Boolean,
        streamId: StreamId,
        suffix: String?,
        records: List<JsonNode>
    ) {
        reformatMetaColumnTimestamps(records)
        super.insertFinalTableRecords(includeCdcDeletedAt, streamId, suffix, records)
    }

    @Throws(Exception::class)
    override fun insertV1RawTableRecords(streamId: StreamId, records: List<JsonNode>) {
        reformatMetaColumnTimestamps(records)
        super.insertV1RawTableRecords(streamId, records)
    }

    @Throws(Exception::class)
    override fun createRawTable(streamId: StreamId) {
        database.execute(
            dslContext
                .createTable(DSL.name(streamId.rawNamespace, streamId.rawName))
                .column(COLUMN_NAME_AB_RAW_ID, SQLDataType.VARCHAR(256).nullable(false))
                .column(COLUMN_NAME_DATA, structType.nullable(false))
                // we use VARCHAR for timestamp values, but TIMESTAMP(6) for extracted+loaded_at.
                // because legacy normalization did that. :shrug:
                .column(COLUMN_NAME_AB_EXTRACTED_AT, SQLDataType.TIMESTAMP(6).nullable(false))
                .column(COLUMN_NAME_AB_LOADED_AT, SQLDataType.TIMESTAMP(6))
                .column(COLUMN_NAME_AB_META, structType.nullable(true))
                .getSQL(ParamType.INLINED),
        )
    }

    @Throws(Exception::class)
    override fun createV1RawTable(v1RawTable: StreamId) {
        database.execute(
            dslContext
                .createTable(DSL.name(v1RawTable.rawNamespace, v1RawTable.rawName))
                .column(
                    COLUMN_NAME_AB_ID,
                    SQLDataType.VARCHAR(36).nullable(false),
                ) // similar to createRawTable - this data type is timestmap, not varchar
                .column(COLUMN_NAME_EMITTED_AT, SQLDataType.TIMESTAMP(6).nullable(false))
                .column(COLUMN_NAME_DATA, structType.nullable(false))
                .getSQL(ParamType.INLINED),
        )
    }

    @Test
    @Throws(Exception::class)
    override fun testCreateTableIncremental() {
        val sql = generator.createTable(incrementalDedupStream, "", false)
        destinationHandler.execute(sql)

        val initialStatuses = destinationHandler.gatherInitialState(listOf(incrementalDedupStream))
        Assertions.assertEquals(1, initialStatuses.size)
        val initialStatus = initialStatuses.first()
        Assertions.assertTrue(initialStatus.isFinalTablePresent)
        Assertions.assertFalse(initialStatus.isSchemaMismatch)
    }

    override fun toJsonValue(valueAsString: String?): Field<*> {
        // mysql lets you just insert json strings directly into json columns
        return DSL.`val`(valueAsString)
    }

    override fun createNamespace(namespace: String) {
        database.execute(
            dslContext
                .createSchemaIfNotExists(nameTransformer.getIdentifier(namespace))
                .getSQL(ParamType.INLINED)
        )
    }

    override fun teardownNamespace(namespace: String) {
        database.execute(
            dslContext
                .dropDatabaseIfExists(nameTransformer.getIdentifier(namespace))
                .getSQL(ParamType.INLINED)
        )
    }

    companion object {
        private lateinit var configJson: JsonNode
        private var dataSource: DataSource? = null
        private lateinit var database: JdbcDatabase
        private val nameTransformer = StandardNameTransformer()
        private val LOGGER: Logger =
            LoggerFactory.getLogger(
                TeradataSqlGeneratorIntegrationTest::class.java,
            )
        @JvmStatic
        @BeforeAll
        @Throws(Exception::class)
        fun setupTeradata() {
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
            dataSource = getDataSource(configJson)
            val destination = TeradataDestination()
            database = destination.getDatabase(dataSource!!)
        }

        protected fun getDataSource(config: JsonNode): DataSource {
            val destination = TeradataDestination()
            val jdbcConfig = destination.toJdbcConfig(config)
            return DataSourceFactory.create(
                jdbcConfig[JdbcUtils.USERNAME_KEY].asText(),
                if (jdbcConfig.has(JdbcUtils.PASSWORD_KEY))
                    jdbcConfig[JdbcUtils.PASSWORD_KEY].asText()
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
            assertCustomParametersDontOverwriteDefaultParameters(
                customProperties,
                defaultProperties
            )
            return MoreMaps.merge(customProperties, defaultProperties)
        }

        protected fun getDefaultConnectionProperties(config: JsonNode): Map<String, String> {
            val destination = TeradataDestination()
            return destination.getDefaultConnectionProperties(config)
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

        @JvmStatic
        @AfterAll
        fun teardownMysql() {
            // Intentionally do nothing.
            // The testcontainer will die at the end of the test run.
        }

        private fun reformatMetaColumnTimestamps(records: List<JsonNode>) {

            for (record in records) {
                reformatTimestampIfPresent(record, COLUMN_NAME_AB_EXTRACTED_AT)
                reformatTimestampIfPresent(record, COLUMN_NAME_EMITTED_AT)
                reformatTimestampIfPresent(record, COLUMN_NAME_AB_LOADED_AT)
            }
        }

        private fun reformatTimestampIfPresent(record: JsonNode, columnNameAbExtractedAt: String) {
            if (record.has(columnNameAbExtractedAt)) {
                val extractedAt = OffsetDateTime.parse(record[columnNameAbExtractedAt].asText())
                val reformattedExtractedAt: String =
                    DateTimeFormatterBuilder().toFormatter().format(extractedAt)
                (record as ObjectNode).put(columnNameAbExtractedAt, reformattedExtractedAt)
            }
        }
    }
}
