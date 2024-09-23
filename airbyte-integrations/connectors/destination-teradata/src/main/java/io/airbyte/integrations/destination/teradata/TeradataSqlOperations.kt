/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.destination.teradata

import com.fasterxml.jackson.databind.JsonNode
import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.integrations.base.AirbyteTraceMessageUtility
import io.airbyte.cdk.integrations.base.JavaBaseConstants
import io.airbyte.cdk.integrations.destination.jdbc.JdbcSqlOperations
import io.airbyte.commons.json.Jsons
import io.airbyte.integrations.destination.teradata.util.JSONStruct
import io.airbyte.protocol.models.v0.AirbyteRecordMessage
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TeradataSqlOperations : JdbcSqlOperations() {
    @Throws(SQLException::class)
    override fun insertRecordsInternal(
        database: JdbcDatabase,
        records: List<AirbyteRecordMessage>,
        schemaName: String,
        tableName: String
    ) {
        if (records.isEmpty()) {
            return
        }
        val insertQueryComponent = String.format(
            "INSERT INTO %s.%s (%s, %s, %s) VALUES (?, ?, ?)", schemaName, tableName,
            JavaBaseConstants.COLUMN_NAME_AB_ID,
            JavaBaseConstants.COLUMN_NAME_DATA,
            JavaBaseConstants.COLUMN_NAME_EMITTED_AT,
        )
        database.execute { con: Connection ->
            try {
                val pstmt = con.prepareStatement(insertQueryComponent)

                for (record in records) {
                    val uuid = UUID.randomUUID().toString()
                    val jsonData =
                        Jsons.serialize<JsonNode>(
                            formatData(record.data),
                        )
                    val emittedAt =
                        Timestamp.from(Instant.ofEpochMilli(record.emittedAt))
                    LOGGER.info(
                        "uuid: $uuid",
                    )
                    LOGGER.info(
                        "jsonData: $jsonData",
                    )
                    LOGGER.info(
                        "emittedAt: $emittedAt",
                    )
                    pstmt.setString(1, uuid)
                    pstmt.setObject(2, JSONStruct("JSON", arrayOf<Any>(jsonData)))
                    pstmt.setTimestamp(3, emittedAt)
                    pstmt.addBatch()
                }

                pstmt.executeBatch()
            } catch (se: SQLException) {
                var ex: SQLException? = se
                while (ex != null) {
                    LOGGER.info(
                        ex.message,
                    )
                    ex = ex.nextException
                }
                AirbyteTraceMessageUtility.emitSystemErrorTrace(
                    se,
                    "Connector failed while inserting records to staging table",
                )
                throw RuntimeException(se)
            } catch (e: Exception) {
                AirbyteTraceMessageUtility.emitSystemErrorTrace(
                    e,
                    "Connector failed while inserting records to staging table",
                )
                throw RuntimeException(e)
            }
        }
    }

    @Throws(Exception::class)
    override fun createSchemaIfNotExists(database: JdbcDatabase, schemaName: String) {
        try {
            database.execute(
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
                AirbyteTraceMessageUtility.emitSystemErrorTrace(
                    e,
                    "Connector failed while creating schema ",
                )
                throw RuntimeException(e)
            }
        }
    }

    @Throws(SQLException::class)
    override fun createTableIfNotExists(
        database: JdbcDatabase,
        schemaName: String,
        tableName: String
    ) {
        try {
            database.execute(createTableQuery(database, schemaName, tableName))
        } catch (e: SQLException) {
            if (e.message!!.contains("already exists")) {
                LOGGER.warn(
                    "Table $schemaName.$tableName already exists.",
                )
            } else {
                AirbyteTraceMessageUtility.emitSystemErrorTrace(
                    e,
                    "Connector failed while creating table ",
                )
                throw RuntimeException(e)
            }
        }
    }

    override fun createTableQuery(
        database: JdbcDatabase,
        schemaName: String,
        tableName: String
    ): String {
        return String.format(
            "CREATE SET TABLE %s.%s, FALLBACK ( %s VARCHAR(256), %s JSON, %s TIMESTAMP(6)) " +
                " UNIQUE PRIMARY INDEX (%s) ",
            schemaName,
            tableName,
            JavaBaseConstants.COLUMN_NAME_AB_ID,
            JavaBaseConstants.COLUMN_NAME_DATA,
            JavaBaseConstants.COLUMN_NAME_EMITTED_AT,
            JavaBaseConstants.COLUMN_NAME_AB_ID,
        )
    }

    @Throws(SQLException::class)
    override fun dropTableIfExists(database: JdbcDatabase, schemaName: String, tableName: String) {
        try {
            database.execute(dropTableIfExistsQueryInternal(schemaName, tableName))
        } catch (e: SQLException) {
            AirbyteTraceMessageUtility.emitSystemErrorTrace(
                e,
                "Connector failed while dropping table $schemaName.$tableName",
            )
        }
    }

    override fun truncateTableQuery(
        database: JdbcDatabase,
        schemaName: String,
        tableName: String
    ): String {
        try {
            return String.format("DELETE %s.%s ALL;\n", schemaName, tableName)
        } catch (e: Exception) {
            AirbyteTraceMessageUtility.emitSystemErrorTrace(
                e,
                "Connector failed while truncating table $schemaName.$tableName",
            )
        }
        return ""
    }

    private fun dropTableIfExistsQueryInternal(schemaName: String, tableName: String): String {
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
