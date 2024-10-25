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
import io.airbyte.protocol.models.v0.AirbyteRecordMessage
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The TeradataSqlOperations class is responsible for performing SQL operations on the Teradata
 * database. It extends the JdbcSqlOperations class to provide functionalities specific to the
 * Teradata integration, including inserting records, creating schemas and tables, and executing SQL
 * transactions.
 */
class TeradataSqlOperations : JdbcSqlOperations() {
    @Throws(SQLException::class)
    /**
     * Inserts a list of records into a specified table in the Teradata database.
     *
     * @param database The JdbcDatabase instance to interact with the database.
     * @param records The list of AirbyteRecordMessage to be inserted.
     * @param schemaName The name of the schema where the table resides.
     * @param tableName The name of the table where records will be inserted.
     * @throws SQLException If an SQL error occurs during the insert operation.
     */
    override fun insertRecordsInternalV2(
        database: JdbcDatabase,
        records: List<PartialAirbyteMessage>,
        schemaName: String?,
        tableName: String?
    ) {

        if (records.isEmpty()) {
            return
        }
        val insertQueryComponent = java.lang.String.format(
            "INSERT INTO %s.%s (%s, %s, %s, %s) VALUES (?, ?, ?, ?)", schemaName, tableName,
            JavaBaseConstants.COLUMN_NAME_AB_RAW_ID,
            JavaBaseConstants.COLUMN_NAME_DATA,
            JavaBaseConstants.COLUMN_NAME_AB_EXTRACTED_AT,
            JavaBaseConstants.COLUMN_NAME_AB_META,
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

    override fun insertRecordsInternal(
        database: JdbcDatabase,
        records: List<PartialAirbyteMessage>,
        schemaName: String?,
        tableName: String?
    ) {
        throw UnsupportedOperationException("Teradata requires V2")
    }
    /** Handles SQL exception */
    private fun handleSQLException(se: SQLException) {
        var ex: SQLException? = se
        val action = "inserting records to staging table"
        while (ex != null) {
            LOGGER.error("SQL error during $action: ${ex.message}")
            ex = ex.nextException
        }
        AirbyteTraceMessageUtility.emitSystemErrorTrace(se, "Connector failed during $action")
        throw RuntimeException(se)
    }

    /**
     * Creates a schema in the Teradata database if it does not already exist.
     *
     * @param database The JdbcDatabase instance to interact with the database.
     * @param schemaName The name of the schema to be created.
     * @throws Exception If an error occurs while creating the schema.
     */
    @Throws(Exception::class)
    override fun createSchemaIfNotExists(database: JdbcDatabase?, schemaName: String?) {
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
                AirbyteTraceMessageUtility.emitSystemErrorTrace(
                    e,
                    "Connector failed while creating schema ",
                )
                throw RuntimeException(e)
            }
        }
    }
    /**
     * Creates a table in the Teradata database if it does not already exist.
     *
     * @param database The JdbcDatabase instance to interact with the database.
     * @param schemaName The name of the schema where the table resides.
     * @param tableName The name of the table to be created.
     * @throws SQLException If an SQL error occurs during the creation of the table.
     */
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
                AirbyteTraceMessageUtility.emitSystemErrorTrace(
                    e,
                    "Connector failed while creating table ",
                )
                throw RuntimeException(e)
            }
        }
    }
    /**
     * Constructs the SQL query for creating a new table in the Teradata database.
     *
     * @param database The JdbcDatabase instance to interact with the database.
     * @param schemaName The name of the schema where the table will be created.
     * @param tableName The name of the table to be created.
     * @return The SQL query string for creating the table.
     */
    override fun createTableQuery(
        database: JdbcDatabase?,
        schemaName: String?,
        tableName: String?
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
    /**
     * Drops a specified table from the Teradata database if it exists.
     *
     * @param database The JdbcDatabase instance to interact with the database.
     * @param schemaName The name of the schema where the table resides.
     * @param tableName The name of the table to be dropped.
     * @throws SQLException If an SQL error occurs during the drop operation.
     */
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
    /**
     * Constructs the SQL query for truncating a table in the Teradata database.
     *
     * @param database The JdbcDatabase instance to interact with the database.
     * @param schemaName The name of the schema where the table resides.
     * @param tableName The name of the table to be truncated.
     * @return The SQL query string for truncating the table.
     */
    override fun truncateTableQuery(
        database: JdbcDatabase?,
        schemaName: String?,
        tableName: String?
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

    /** Drops given table */
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
    /**
     * Executes a list of SQL queries as a single transaction.
     *
     * @param database The JdbcDatabase instance to interact with the database.
     * @param queries The list of SQL queries to be executed.
     * @throws Exception If an error occurs during the transaction execution.
     */
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
        private val LOGGER: Logger =
            LoggerFactory.getLogger(
                TeradataSqlOperations::class.java,
            )
    }
}
