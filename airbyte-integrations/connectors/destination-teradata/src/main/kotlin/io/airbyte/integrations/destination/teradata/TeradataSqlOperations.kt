/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.destination.teradata

import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.integrations.base.AirbyteTraceMessageUtility
import io.airbyte.cdk.integrations.base.JavaBaseConstants
import io.airbyte.cdk.integrations.destination.async.model.PartialAirbyteMessage
import io.airbyte.cdk.integrations.destination.jdbc.JdbcSqlOperations
import io.airbyte.commons.json.Jsons
import io.airbyte.integrations.destination.teradata.util.JSONStruct
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TeradataSqlOperations : JdbcSqlOperations() {


    @Throws(Exception::class)
    override fun createSchemaIfNotExists(database: JdbcDatabase?, schemaName: String) {
        try {
            database?.execute(
                String.format(
                    "CREATE DATABASE \"%s\" AS PERMANENT = 120e6, SPOOL = 120e6;",
                    schemaName,
                ),
            )
        } catch (e: SQLException) {
            if (e.message!!.contains("already exists")) {
                LOGGER.warn(
                    "Database $schemaName already exists.",
                )
            } else {
                throw RuntimeException(e)
            }
        }
    }

    @Throws(SQLException::class)
    override fun createTableIfNotExists(
        database: JdbcDatabase,
        schemaName: String?,
        tableName: String?
    ) {
        try {
            database.execute(createTableQuery(database, schemaName, tableName))
        } catch (e: SQLException) {
            if (e.message!!.contains("already exists")) {
                LOGGER.warn(
                    "Table $schemaName.$tableName already exists.",
                )
            } else {
                throw RuntimeException(e)
            }
        }
    }

    override fun createTableQuery(
        database: JdbcDatabase?,
        schemaName: String?,
        tableName: String?
    ): String {
        return String.format(
            """
        CREATE TABLE %s.%s, FALLBACK  (
          %s VARCHAR(256),
          %s JSON,
          %s TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP(6),
          %s TIMESTAMP WITH TIME ZONE DEFAULT NULL,
          %s JSON,
          %s BIGINT
          ) UNIQUE PRIMARY INDEX (%s);
        
        """.trimIndent(),
            schemaName,
            tableName,
            JavaBaseConstants.COLUMN_NAME_AB_RAW_ID,
            JavaBaseConstants.COLUMN_NAME_DATA,
            JavaBaseConstants.COLUMN_NAME_AB_EXTRACTED_AT,
            JavaBaseConstants.COLUMN_NAME_AB_LOADED_AT,
            JavaBaseConstants.COLUMN_NAME_AB_META,
            JavaBaseConstants.COLUMN_NAME_AB_GENERATION_ID,
            JavaBaseConstants.COLUMN_NAME_AB_RAW_ID
            )
    }

    @Throws(SQLException::class)
    override fun dropTableIfExists(database: JdbcDatabase, schemaName: String?, tableName: String?) {
        try {
            database.execute(dropTableIfExistsQueryInternal(schemaName, tableName))
        } catch (e: SQLException) {
            AirbyteTraceMessageUtility.emitSystemErrorTrace(
                e,
                "Connector failed while dropping table $schemaName.$tableName",
            )
        }
    }



    override fun overwriteRawTable(database: JdbcDatabase, rawNamespace: String, rawName: String) {
        TODO("Not yet implemented")
    }



    override fun insertRecordsInternalV2(
        database: JdbcDatabase,
        records: List<PartialAirbyteMessage>,
        schemaName: String?,
        tableName: String?,
        syncId: Long,
        generationId: Long
    ) {

        if (records.isEmpty()) {
            return
        }
        val insertQueryComponent = java.lang.String.format(
            "INSERT INTO %s.%s (%s, %s, %s, %s, %s) VALUES (?, ?, ?, ?, ?)", schemaName, tableName,
            JavaBaseConstants.COLUMN_NAME_AB_RAW_ID,
            JavaBaseConstants.COLUMN_NAME_DATA,
            JavaBaseConstants.COLUMN_NAME_AB_EXTRACTED_AT,
            JavaBaseConstants.COLUMN_NAME_AB_META,
            JavaBaseConstants.COLUMN_NAME_AB_GENERATION_ID
        )
        LOGGER.info("Satish - TeradataSqlOperations - insertRecordsInternalV2 - insert query - {}", insertQueryComponent)
        database.execute { con ->
            try {
                val stmt = con.prepareStatement(insertQueryComponent)

                for (record in records) {
                    val uuid = UUID.randomUUID().toString()
                    val jsonData = record.serialized
                    val airbyteMeta =
                        if (record.record!!.meta == null) {
                            "{\"changes\":[]}"
                        } else {
                            Jsons.serialize(record.record!!.meta)
                        }
                    val extractedAt =
                        Timestamp.from(Instant.ofEpochMilli(record.record!!.emittedAt))
                    var i = 0;
                    stmt.setString(++i, uuid)

                    stmt.setObject(
                        ++i,
                        JSONStruct(
                            "JSON",
                            arrayOf(jsonData),
                        ),
                    )
                    stmt.setTimestamp(++i, extractedAt)
                    stmt.setString(++i, airbyteMeta)
                    stmt.setLong(++i, generationId)
                    stmt.addBatch()
                    LOGGER.info("Satish - TeradataSqlOperations - insertRecordsInternalV2 - uuid - {}", uuid)
                    LOGGER.info("Satish - TeradataSqlOperations - insertRecordsInternalV2 - jsonData - {}", jsonData)
                    LOGGER.info("Satish - TeradataSqlOperations - insertRecordsInternalV2 - airbyteMeta - {}", airbyteMeta)
                    LOGGER.info("Satish - TeradataSqlOperations - insertRecordsInternalV2 - extractedAt - {}", extractedAt)
                }
                stmt.executeBatch()
            } catch (e: Exception) {
                throw Exception(e)
            }
        }
    }


    override fun truncateTableQuery(
        database: JdbcDatabase,
        schemaName: String,
        tableName: String
    ): String {
        try {
            return String.format("DELETE %s.%s ALL;\n", schemaName, tableName)
        } catch (e: java.lang.Exception) {
            AirbyteTraceMessageUtility.emitSystemErrorTrace(
                e,
                "Connector failed while truncating table $schemaName.$tableName",
            )
        }
        return ""
    }

    private fun dropTableIfExistsQueryInternal(schemaName: String?, tableName: String?): String {
        try {
            return String.format("DROP TABLE  %s.%s;\n", schemaName, tableName)
        } catch (e: Exception) {
            AirbyteTraceMessageUtility.emitSystemErrorTrace(
                e,
                "Connector failed while dropping table $schemaName.$tableName",
            )
        }
        return ""
    }

    @Throws(Exception::class)
    override fun executeTransaction(database: JdbcDatabase, queries: List<String>) {
        val appendedQueries = StringBuilder()
        try {
            for (query in queries) {
                appendedQueries.append(query)
            }
            database.execute(appendedQueries.toString())
        } catch (e: SQLException) {
            AirbyteTraceMessageUtility.emitSystemErrorTrace(
                e,
                "Connector failed while executing queries : $appendedQueries",
            )
        }
    }




    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(
            TeradataSqlOperations::class.java,
        )
    }
}
