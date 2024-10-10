package io.airbyte.integrations.destination.teradata.typing_deduping

import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_EXTRACTED_AT
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_ID
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_LOADED_AT
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_META
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_RAW_ID
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_DATA
import io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_EMITTED_AT
import io.airbyte.cdk.integrations.destination.StandardNameTransformer
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcSqlGenerator
import io.airbyte.integrations.base.destination.typing_deduping.AirbyteProtocolType
import io.airbyte.integrations.base.destination.typing_deduping.AirbyteType
import io.airbyte.integrations.base.destination.typing_deduping.ColumnId
import io.airbyte.integrations.base.destination.typing_deduping.Sql
import io.airbyte.integrations.base.destination.typing_deduping.Sql.Companion.of
import io.airbyte.integrations.base.destination.typing_deduping.Sql.Companion.separately
import io.airbyte.integrations.base.destination.typing_deduping.Sql.Companion.transactionally
import io.airbyte.integrations.base.destination.typing_deduping.StreamConfig
import io.airbyte.integrations.base.destination.typing_deduping.StreamId
import java.util.*
import java.util.stream.Collectors
import org.jooq.Condition
import org.jooq.DataType
import org.jooq.Field
import org.jooq.Name
import org.jooq.SQLDialect
import org.jooq.SortField
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.name
import org.jooq.impl.DSL.quotedName
import org.jooq.impl.DSL.rowNumber
import org.jooq.impl.DSL.sql
import org.jooq.impl.DefaultDataType
import org.jooq.impl.SQLDataType
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class TeradataSqlGenerator(
) : JdbcSqlGenerator(namingTransformer = StandardNameTransformer()) {



    override fun createSchema(schema: String): Sql {
        val query = of(
            String.format(
                "CREATE DATABASE \"%s\" AS PERMANENT = 120e6, SPOOL = 120e6;",
                schema,
            ),
        )
        LOGGER.info("Satish - TeradataSqlGenerator - createSchema - query - {}", query)
        return query
    }


    override val arrayType: DataType<*>
        get() = JSON_TYPE
    override val dialect: SQLDialect
        get() = SQLDialect.DEFAULT
    override val structType: DataType<*>
        get() = JSON_TYPE
    override val widestType: DataType<*>
        get() = JSON_TYPE




    override fun buildAirbyteMetaColumn(columns: LinkedHashMap<ColumnId, AirbyteType>): Field<*> {
        // return inline("{}").`as`(COLUMN_NAME_AB_META)
        val query = field(
            sql(
                """COALESCE($COLUMN_NAME_AB_META, CAST('{"changes":[]}' AS JSON))""",
            ),
        )
            .`as`(COLUMN_NAME_AB_META)
        LOGGER.info("Satish - TeradataSqlGenerator - buildAirbyteMetaColumn - query - {}", query)
        return query
    }

    override fun cdcDeletedAtNotNullCondition(): Condition {
        val temp = field(name(COLUMN_NAME_AB_LOADED_AT)).isNotNull()
            .and(extractColumnAsJson(cdcDeletedAtColumn).notEqual("null"));
        LOGGER.info("Satish - TeradataSqlGenerator - cdcDeletedAtNotNullCondition - temp - {}", temp)
        return temp
    }
    private fun extractColumnAsJson(column: ColumnId): Field<Any> {
        val temp = field((("cast(" + name(COLUMN_NAME_DATA)) + ".JSONExtract('$." + field(column.originalName)) + "') as VARCHAR(100) )")
        LOGGER.info("Satish - TeradataSqlGenerator - extractColumnAsJson - temp - {}", temp)
        return temp
    }



    override fun extractRawDataFields(
        columns: LinkedHashMap<ColumnId, AirbyteType>,
        useExpensiveSaferCasting: Boolean
    ): MutableList<Field<*>> {
        val fields: MutableList<Field<*>> = ArrayList()
        LOGGER.info("Satish - TeradataSqlGenerator - extractRawDataFields - columns - {}", columns)
        columns.forEach { (key, value) ->
            LOGGER.info("Satish - TeradataSqlGenerator - extractRawDataFields - key - {} - value - {}", key, value)
            if (value == AirbyteProtocolType.UNKNOWN || value.typeName == "STRUCT" || value.typeName == "ARRAY") {
                fields.add(
                    field(
                        (("cast(" + name(COLUMN_NAME_DATA)) + ".JSONExtract('$." + field(
                            key.originalName,
                        )) + "') as " + toDialectType(value) + ")",
                    ).`as`(key.name),
                )
            } else if (value == AirbyteProtocolType.TIMESTAMP_WITH_TIMEZONE) {
                fields.add(
                    field(
                        ((((((("case when (REGEXP_SIMILAR(" + name(COLUMN_NAME_DATA)) + ".JSONExtractValue('$." + field(
                            key.originalName,
                        )) + "'), '^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}|Z\$') = 1 OR " +
                            "REGEXP_SIMILAR(" + name(COLUMN_NAME_DATA)) + ".JSONExtractValue('$." + field(
                            key.originalName,
                        )) + "'), '^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.\\d{6}\$') = 1) " +
                            "  then cast("
                            + name(COLUMN_NAME_DATA)) + ".JSONExtractValue('$." + field(
                            key.originalName,
                        )) + "') as " + toDialectType(value) + ") else null end "),
                    ).`as`(key.name),
                )
            } else if (value == AirbyteProtocolType.TIME_WITH_TIMEZONE) {
                fields.add(
                    field(
                        ((((((("case when (REGEXP_SIMILAR(" + name(COLUMN_NAME_DATA)) + ".JSONExtractValue('$." + field(
                            key.originalName,
                        )) + "'), '^\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}|Z\$') = 1 OR " +
                            "REGEXP_SIMILAR(" + name(COLUMN_NAME_DATA)) + ".JSONExtractValue('$." + field(
                            key.originalName,
                        )) + "'), '^\\d{2}:\\d{2}:\\d{2}.\\d{6}\$') = 1) " +
                            "  then cast("
                            + name(COLUMN_NAME_DATA)) + ".JSONExtractValue('$." + field(
                            key.originalName,
                        )) + "') as " + toDialectType(value) + ") else null end "),
                    ).`as`(key.name),
                )
            } else if (value == AirbyteProtocolType.STRING) {
                fields.add(
                    field(
                        ((((((((("case when "
                            + "cast(" + name(COLUMN_NAME_DATA)) + ".JSONExtract('$." + field(
                            key.originalName,
                        )) + ".*') as " + toDialectType(value) + ") is not null "
                            + "then SUBSTRING(cast(" + name(COLUMN_NAME_DATA)) + ".JSONExtract('$." + field(
                            key.originalName,
                        )) + "') as " + toDialectType(value) + ") FROM 2 FOR LENGTH (cast(" + name(
                            COLUMN_NAME_DATA,
                        )) + ".JSONExtract('$." + field(key.originalName)) + "') as " + toDialectType(
                            value,
                        ) + "))" + "-2) "
                            + "else cast(" + name(COLUMN_NAME_DATA)) + ".JSONExtractValue('$." + field(
                            key.originalName,
                        )) + "') as " + toDialectType(value) + ") END"),
                    ).`as`(key.name),
                )
            } else {
                fields.add(
                    field(
                        (("cast(" + name(COLUMN_NAME_DATA)) + ".JSONExtractValue('$." + field(
                            key.originalName,
                        )) + "') as " + toDialectType(value) + ")",
                    ).`as`(key.name),
                )
            }
        }
        LOGGER.info("Satish - TeradataSqlGenerator - extractRawDataFields - fields - {}", fields)
        return fields
    }

    override fun getRowNumber(
        primaryKey: List<ColumnId>,
        cursorField: Optional<ColumnId>
    ): Field<Int> {
        LOGGER.info("Satish - TeradataSqlGenerator - getRowNumber - primaryKey - {}", primaryKey)
        val primaryKeyFields: List<Field<*>> =
            primaryKey
                .stream()
                .map { columnId: ColumnId ->
                    field(
                        quotedName(columnId.name),
                    )
                }
                .collect(Collectors.toList<Field<*>>())
        LOGGER.info("Satish - TeradataSqlGenerator - getRowNumber - primaryKeyFields - {}", primaryKeyFields)
        val orderedFields: MutableList<SortField<Any>> = ArrayList()


        cursorField.ifPresent { columnId ->
            orderedFields.add(field(quotedName(columnId.name)).desc().nullsLast(),
            )
        }
        LOGGER.info("Satish - TeradataSqlGenerator - getRowNumber - cursorField - {}", cursorField)

        orderedFields.add(field("{0}", quotedName(COLUMN_NAME_AB_EXTRACTED_AT)).desc());
        LOGGER.info("Satish - TeradataSqlGenerator - getRowNumber - orderedFields - {}", orderedFields)
        val query = rowNumber()
            .over()
            .partitionBy(primaryKeyFields)
            .orderBy(orderedFields)
            .`as`(ROW_NUMBER_COLUMN_NAME)
        LOGGER.info("Satish - TeradataSqlGenerator - getRowNumber - query - {}", query)
        return query
    }

    override fun toDialectType(airbyteProtocolType: AirbyteProtocolType): DataType<*> {
        LOGGER.info("Satish - TeradataSqlGenerator - toDialectType - airbyteProtocolType - {} ", airbyteProtocolType)
        val s = when (airbyteProtocolType) {
            AirbyteProtocolType.STRING -> SQLDataType.VARCHAR(64000)
            AirbyteProtocolType.BOOLEAN -> SQLDataType.BOOLEAN
            AirbyteProtocolType.INTEGER -> SQLDataType.INTEGER
            AirbyteProtocolType.NUMBER -> SQLDataType.FLOAT
            else -> super.toDialectType(airbyteProtocolType)
        }
        LOGGER.info("Satish - TeradataSqlGenerator - toDialectType - return type - {} ", s)
        return s
    }

    override fun createTable(stream: StreamConfig, suffix: String, force: Boolean): Sql {
        LOGGER.info("TeradataSqlGenerator - CreateTable - tablename - {}, force? - {}", stream.id.finalName, force)
        val finalTableIdentifier: String =
            stream.id.finalName + suffix.lowercase(Locale.getDefault())
        LOGGER.info("TeradataSqlGenerator - CreateTable - finalTableIdentifier- {}", finalTableIdentifier)
        if (!force) {
            return separately(
                createTableSql(
                    stream.id.finalNamespace,
                    finalTableIdentifier,
                    stream.columns,
                ),
            )
        }

        val sl = separately(
            java.lang.String.format(
                "DROP TABLE %s.%s;",
                stream.id.finalNamespace,
                finalTableIdentifier,
            ),
            createTableSql(
                stream.id.finalNamespace,
                finalTableIdentifier,
                stream.columns,
            ),
        )
        LOGGER.info("TeradataSqlGenerator - CreateTable -  sql - {}", sl)
        return sl
    }


    //TODO: Check with parten implementation if something is missing
    override fun overwriteFinalTable(stream: StreamId, finalSuffix: String): Sql {
        val spaceName: String = stream.finalNamespace
        val tableName: String = stream.finalName + finalSuffix
        val newTableName: String = stream.finalName


        val query = separately(
            String.format("DROP TABLE %s.%s;", spaceName, newTableName),
            String.format(
                "RENAME TABLE %s.%s TO %s.%s;",
                spaceName,
                tableName,
                spaceName,
                newTableName,
            ),
        )
        LOGGER.info("Satish - TeradataSqlGenerator - overwriteFinalTable - query: {}", query)
        return query
    }

    override fun migrateFromV1toV2(streamId: StreamId, namespace: String, tableName: String): Sql {
        LOGGER.info("namespace: {}, tablename: {}", namespace, tableName)
        LOGGER.info(
            "stream id namespace: {}, stream id tablename: {}",
            streamId.rawNamespace,
            streamId.rawName,
        )
        val rawTableName: Name = name(streamId.rawNamespace, streamId.rawName)
        return transactionally(
            createV2RawTableFromV1Table(rawTableName, namespace, tableName),
        )
    }

    public override fun createV2RawTableFromV1Table(
        rawTableName: Name,
        namespace: String,
        tableName: String
    ): String {
        val query = java.lang.String.format(
            "CREATE TABLE %s AS ( SELECT %s %s, %s %s, CAST(NULL AS TIMESTAMP WITH TIME ZONE) %s, %s %s, CAST(NULL AS JSON) %s FROM %s.%s) WITH DATA",
            rawTableName,
            COLUMN_NAME_AB_ID,
            COLUMN_NAME_AB_RAW_ID,
            COLUMN_NAME_EMITTED_AT,
            COLUMN_NAME_AB_EXTRACTED_AT,
            COLUMN_NAME_AB_LOADED_AT,
            COLUMN_NAME_DATA,
            COLUMN_NAME_DATA,
            COLUMN_NAME_AB_META,
            namespace,
            tableName,
        )
        LOGGER.info("create createV2RawTableFromV1Table: {}", query)
        return query
    }


   /* override fun insertAndDeleteTransaction(
        streamConfig: StreamConfig,
        finalSuffix: String?,
        minRawTimestamp: Optional<Instant>,
        useExpensiveSaferCasting: Boolean
    ): Sql {
        val finalSchema: String = streamConfig.id.finalNamespace
        val finalTable: String = streamConfig.id.finalName +
            (finalSuffix?.lowercase(Locale.getDefault()) ?: "")
        val rawSchema: String = streamConfig.id.rawNamespace
        val rawTable: String = streamConfig.id.rawName

        // Poor person's guarantee of ordering of fields by using same source of ordered list of
        // columns to
        // generate fields.
        val rawTableRowsWithCast =
            name(TYPING_CTE_ALIAS).`as`<Record>(
                selectFromRawTable(
                    rawSchema,
                    rawTable,
                    streamConfig.columns,
                    getFinalTableMetaColumns(false),
                    rawTableCondition(
                        streamConfig.destinationSyncMode,
                        streamConfig.columns.containsKey(cdcDeletedAtColumn),
                        minRawTimestamp,
                    ),
                    useExpensiveSaferCasting,
                ),
            )
        LOGGER.info("Satish - TeradataSqlGenerator - insertAndDeleteTransaction - rawTableRowsWithCast: {}", rawTableRowsWithCast)
        val finalTableFields = buildFinalTableFields(
            streamConfig.columns,
            getFinalTableMetaColumns(true),
        )
        LOGGER.info("Satish - TeradataSqlGenerator - insertAndDeleteTransaction - finalTableFields: {}", finalTableFields)
        val rowNumber = getRowNumber(
            streamConfig.primaryKey,
            streamConfig.cursor,
        )
        LOGGER.info("Satish - TeradataSqlGenerator - insertAndDeleteTransaction - rowNumber: {}", rowNumber)
        val filteredRows =
            name(NUMBERED_ROWS_CTE_ALIAS).`as`(
                DSL.select(finalTableFields)
                    .select(rowNumber)
                    .from(rawTableRowsWithCast),
            )
        LOGGER.info("Satish - TeradataSqlGenerator - insertAndDeleteTransaction - filteredRows: {}", filteredRows)


        // Used for append-dedupe mode.
        val insertStmtWithDedupe =
            insertIntoFinalTable(
                finalSchema,
                finalTable,
                streamConfig.columns,
                getFinalTableMetaColumns(true),
            )
                .select(
                    DSL.with(rawTableRowsWithCast)
                        .with(filteredRows)
                        .select(finalTableFields)
                        .from(filteredRows)
                        .where(
                            field<Int>(
                                name(ROW_NUMBER_COLUMN_NAME),
                                Int::class.java,
                            ).eq(1),
                        ),
                )
                .getSQL(ParamType.INLINED)
        LOGGER.info("Satish - TeradataSqlGenerator - insertAndDeleteTransaction - insertStmtWithDedupe: {}", insertStmtWithDedupe)
        // Used for append and overwrite modes.
        val insertStmt =
            insertIntoFinalTable(
                finalSchema,
                finalTable,
                streamConfig.columns,
                getFinalTableMetaColumns(true),
            )
                .select(
                    DSL.with(rawTableRowsWithCast)
                        .select(finalTableFields)
                        .from(rawTableRowsWithCast),
                )
                .getSQL(ParamType.INLINED)
        LOGGER.info("Satish - TeradataSqlGenerator - insertAndDeleteTransaction - insertStmt: {}", insertStmt)

        val deleteStmt = deleteFromFinalTable(
            finalSchema,
            finalTable,
            streamConfig.primaryKey,
            streamConfig.cursor,
        )
        LOGGER.info("Satish - TeradataSqlGenerator - insertAndDeleteTransaction - deleteStmt: {}", deleteStmt)
        val deleteCdcDeletesStmt =
            if (streamConfig.columns.containsKey(cdcDeletedAtColumn))
                deleteFromFinalTableCdcDeletes(finalSchema, finalTable)
            else
                ""
        LOGGER.info("Satish - TeradataSqlGenerator - insertAndDeleteTransaction - deleteCdcDeletesStmt: {}", deleteCdcDeletesStmt)
        val checkpointStmt = checkpointRawTable(rawSchema, rawTable, minRawTimestamp)
        LOGGER.info("Satish - TeradataSqlGenerator - insertAndDeleteTransaction - checkpointStmt: {}", checkpointStmt)
        if (streamConfig.destinationSyncMode !== DestinationSyncMode.APPEND_DEDUP) {
            return transactionally(insertStmt, checkpointStmt)
        }

        // For append-dedupe
        return transactionally(
            insertStmtWithDedupe,
            deleteStmt,
            deleteCdcDeletesStmt,
            checkpointStmt,
        )
    }
*/

    companion object {

        private val LOGGER: Logger = LoggerFactory.getLogger(TeradataSqlGenerator::class.java)
        const val TYPING_CTE_ALIAS = "intermediate_data"
        const val NUMBERED_ROWS_CTE_ALIAS = "numbered_rows"
        val JSON_TYPE: DefaultDataType<Any> =
            DefaultDataType(
                null,
                Any::class.java,
                "json",
            )
    }

}
