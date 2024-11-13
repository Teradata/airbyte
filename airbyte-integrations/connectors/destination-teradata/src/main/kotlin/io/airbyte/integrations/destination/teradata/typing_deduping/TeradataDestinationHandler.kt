package io.airbyte.integrations.destination.teradata.typing_deduping

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.integrations.destination.jdbc.JdbcGenerationHandler
import io.airbyte.cdk.integrations.destination.jdbc.TableDefinition
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcDestinationHandler
import io.airbyte.commons.json.Jsons
import io.airbyte.integrations.base.destination.typing_deduping.AirbyteProtocolType
import io.airbyte.integrations.base.destination.typing_deduping.AirbyteType
import io.airbyte.integrations.base.destination.typing_deduping.Array
import io.airbyte.integrations.base.destination.typing_deduping.Sql
import io.airbyte.integrations.base.destination.typing_deduping.StreamId
import io.airbyte.integrations.base.destination.typing_deduping.Struct
import io.airbyte.integrations.base.destination.typing_deduping.Union
import io.airbyte.integrations.base.destination.typing_deduping.UnsupportedOneOf
import io.airbyte.integrations.base.destination.typing_deduping.migrators.MinimumDestinationState
import io.airbyte.protocol.models.v0.AirbyteStreamNameNamespacePair
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.*
import org.jooq.Condition
import org.jooq.SQLDialect
import org.jooq.conf.ParamType
import org.jooq.impl.DSL
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.quotedName
import org.jooq.impl.DSL.table
import org.jooq.impl.SQLDataType
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class TeradataDestinationHandler(
    databaseName: String?,
    jdbcDatabase: JdbcDatabase,
    rawTableSchema: String,
    generationHandler: JdbcGenerationHandler,
) :
    JdbcDestinationHandler<MinimumDestinationState>(
        null,
        jdbcDatabase,
        rawTableSchema,
        SQLDialect.DEFAULT,
        generationHandler = generationHandler
    ){
    override fun toDestinationState(json: JsonNode): MinimumDestinationState =
        MinimumDestinationState.Impl(
            json.hasNonNull("needsSoftReset") && json["needsSoftReset"].asBoolean(),
        )

    override fun createNamespaces(schemas: Set<String>) {
        TODO("Not yet implemented")
    }


    override fun toJdbcTypeName(airbyteType: AirbyteType): String {
        LOGGER.info("Satish - TeradataDestinationHandler - toJdbcTypeName : {}" , airbyteType.typeName)
        val test =
            if (airbyteType is AirbyteProtocolType) {
                LOGGER.info("Satish - TeradataDestinationHandler - toJdbcTypeName : comanion type")
                Companion.toJdbcTypeName(airbyteType)
            } else {
                LOGGER.info("Satish - TeradataDestinationHandler - toJdbcTypeName : non comanion type")
                when (airbyteType.typeName) {
                    Struct.TYPE,
                    UnsupportedOneOf.TYPE,
                    Array.TYPE -> "json"

                    Union.TYPE -> toJdbcTypeName((airbyteType as Union).chooseType())
                    else -> throw IllegalArgumentException("Unsupported AirbyteType: $airbyteType")
                }
            }
        return test
    }


   /* @Throws(Exception::class)
    override fun isFinalTableEmpty(id: StreamId): Boolean {
        val query = !jdbcDatabase.queryBoolean(
            dslContext
                .select(
                    DSL.case_()
                        .`when`<Int>(
                            field<Int>(
                                DSL.select<Int>(DSL.count())
                                    .from(DSL.name(id.finalNamespace, id.finalName)),
                            ).gt(0),
                            DSL.inline(1),
                        )
                        .otherwise(DSL.inline(0))
                        .`as`("exists_flag"),
                )
                .getSQL(ParamType.INLINED),
        )
        LOGGER.info("Satish - TeradataDestinationHandler - isFinalTableEmpty - query - {}", query)
        return query
    } */



    override fun getDeleteStatesSql(
        destinationStates: Map<StreamId, MinimumDestinationState>
    ): String {
        val query = dslContext
            .deleteFrom(
                table(
                    quotedName(
                        rawTableNamespace,
                        DESTINATION_STATE_TABLE_NAME,
                    ),
                ),
            )
            .where(
                destinationStates.keys
                    .stream()
                    .map { streamId: StreamId ->
                        field(quotedName(DESTINATION_STATE_TABLE_COLUMN_NAME))
                            .eq(streamId.originalName)
                            .and(
                                field(quotedName(DESTINATION_STATE_TABLE_COLUMN_NAMESPACE))
                                    .eq(streamId.originalNamespace),
                            )
                    }
                    .reduce(DSL.noCondition()) { obj: Condition, arg2: Condition? ->
                        obj.or(arg2)
                    },
            )
            .getSQL(ParamType.INLINED)
        LOGGER.info("Satish - TeradataDestinationHandler - getDeleteStatesSql - delete query - {}", query)
        return query
    }

    override fun commitDestinationStates(destinationStates: Map<StreamId, MinimumDestinationState>) {
        try {
            if (destinationStates.isEmpty()) {
                return
            }

            // Delete all state records where the stream name+namespace match one of our states
            val sqlStatementsDestinationState: MutableList<String> = ArrayList()
            sqlStatementsDestinationState.add(getDeleteStatesSql(destinationStates))

            for ((streamId, value) in destinationStates) {
                val stateJson = Jsons.serialize(value)


                // Reinsert all of our states
                val insertStatesStep: String = dslContext
                    .insertInto(
                        table(
                            quotedName(
                                rawTableNamespace,
                                DESTINATION_STATE_TABLE_NAME,
                            ),
                        ),
                    )
                    .columns(
                        field(quotedName(DESTINATION_STATE_TABLE_COLUMN_NAME), String::class.java),
                        field(
                            quotedName(DESTINATION_STATE_TABLE_COLUMN_NAMESPACE),
                            String::class.java,
                        ),
                        field(quotedName(DESTINATION_STATE_TABLE_COLUMN_STATE), String::class.java),
                        field(quotedName(DESTINATION_STATE_TABLE_COLUMN_UPDATED_AT)),
                    ).values(
                        streamId.originalName,
                        streamId.originalNamespace,
                        stateJson,
                        null,
                    ).getSQL(ParamType.INLINED)
                LOGGER.info("Satish - TeradataDestinationHandler - commitDestinationStates - insertStatesStep - {}", insertStatesStep)
                sqlStatementsDestinationState.add(insertStatesStep)
            }


            executeWithinTransaction(sqlStatementsDestinationState)
        } catch (e: java.lang.Exception) {
            LOGGER.warn("Failed to commit destination states", e)
        }
    }

    override fun getAllDestinationStates(): Map<AirbyteStreamNameNamespacePair, MinimumDestinationState> {
        try {
            val sqlStatement: String = dslContext
                .createTable(quotedName(rawTableNamespace, DESTINATION_STATE_TABLE_NAME))
                .column(quotedName(DESTINATION_STATE_TABLE_COLUMN_NAME), SQLDataType.VARCHAR(256))
                .column(
                    quotedName(DESTINATION_STATE_TABLE_COLUMN_NAMESPACE),
                    SQLDataType.VARCHAR(256),
                )
                .column(quotedName(DESTINATION_STATE_TABLE_COLUMN_STATE), SQLDataType.VARCHAR(256))
                .column(
                    quotedName(DESTINATION_STATE_TABLE_COLUMN_UPDATED_AT),
                    stateTableUpdatedAtType,
                )
                .getSQL(ParamType.INLINED)
            LOGGER.info("Satish - TeradataDestinationHandler - getAllDestinationStates - sqlStatement - {}", sqlStatement)
            try {
                jdbcDatabase.execute(sqlStatement)
            } catch (e: SQLException) {
                if (e.message!!.contains("already exists")) {
                    LOGGER.warn("Table already exists: {}", sqlStatement)
                } else {
                    throw java.lang.RuntimeException(e)
                }
            }

            // Fetch all records from it. We _could_ filter down to just our streams... but meh.
            // This is small
            // data.
            return jdbcDatabase
                .queryJsons(
                    dslContext
                        .select(
                            field(quotedName(DESTINATION_STATE_TABLE_COLUMN_NAME)),
                            field(quotedName(DESTINATION_STATE_TABLE_COLUMN_NAMESPACE)),
                            field(quotedName(DESTINATION_STATE_TABLE_COLUMN_STATE)),
                            field(quotedName(DESTINATION_STATE_TABLE_COLUMN_UPDATED_AT)),
                        )
                        .from(
                            quotedName(
                                rawTableNamespace,
                                DESTINATION_STATE_TABLE_NAME,
                            ),
                        )
                        .sql,
                )
                .map { recordJson: JsonNode ->
                    // Forcibly downcase all key names.
                    // This is to handle any destinations that upcase the column names.
                    // For example - Snowflake with QUOTED_IDENTIFIERS_IGNORE_CASE=TRUE.
                    val record = recordJson as ObjectNode
                    val newFields: HashMap<String, JsonNode> = HashMap()

                    val it = record.fieldNames()
                    while (it.hasNext()) {
                        val fieldName = it.next()
                        // We can't directly call record.set here, because that will raise a
                        // ConcurrentModificationException on the fieldnames iterator.
                        // Instead, build up a map of new fields and set them all at once.
                        newFields[fieldName.lowercase(Locale.getDefault())] = record[fieldName]
                    }
                    record.setAll<JsonNode>(newFields)

                    record
                }
                .sortedBy {
                    // Sort by updated_at, so that if there are duplicate state,
                    // the most recent state is the one that gets used.
                    // That shouldn't typically happen, but let's be defensive.
                    val updatedAt = it.get(DESTINATION_STATE_TABLE_COLUMN_UPDATED_AT)?.asText()
                    if (updatedAt != null) {
                        OffsetDateTime.parse(updatedAt)
                    } else {
                        OffsetDateTime.MIN
                    }
                }
                .associate {
                    val stateTextNode: JsonNode? = it.get(DESTINATION_STATE_TABLE_COLUMN_STATE)
                    val stateNode =
                        if (stateTextNode != null) Jsons.deserialize(stateTextNode.asText())
                        else Jsons.emptyObject()
                    val airbyteStreamNameNamespacePair =
                        AirbyteStreamNameNamespacePair(
                            it.get(DESTINATION_STATE_TABLE_COLUMN_NAME)?.asText(),
                            it.get(DESTINATION_STATE_TABLE_COLUMN_NAMESPACE)?.asText(),
                        )

                    airbyteStreamNameNamespacePair to toDestinationState(stateNode)
                }


        } catch (e: java.lang.Exception) {
            LOGGER.warn("Failed to retrieve destination states", e)
            return emptyMap()
        }
    }

    override fun findExistingTable(id: StreamId): Optional<TableDefinition> =
        findExistingTable(jdbcDatabase, id.finalNamespace, null, id.finalName)


    override fun execute(sql: Sql) {
        val transactions: List<List<String>> = sql.transactions
        for (transaction in transactions) {
            try {
                LOGGER.info("Satish - TeradataDestinationHandler - execute - query {}", transaction)
                jdbcDatabase.executeWithinTransaction(transaction)
            } catch (e: SQLException) {
                if (e.message!!.contains("with the specified name already exists")) {
                    LOGGER.warn(e.message)
                } else if (e.message!!.contains("does not exist")) {
                    LOGGER.warn(e.message)
                } else {
                    throw e
                }
            }
        }
    }
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(TeradataDestinationHandler::class.java)
        private const val DESTINATION_STATE_TABLE_COLUMN_STATE: String = "destination_state"
        private const val DESTINATION_STATE_TABLE_COLUMN_UPDATED_AT: String = "updated_at"
        private const val DESTINATION_STATE_TABLE_NAME = "_airbyte_destination_state"
        private const val DESTINATION_STATE_TABLE_COLUMN_NAME = "name"
        private const val DESTINATION_STATE_TABLE_COLUMN_NAMESPACE = "namespace"

        private fun toJdbcTypeName(airbyteProtocolType: AirbyteProtocolType): String {
            LOGGER.info("Satish toJdbcTypeName: {}", airbyteProtocolType)
            val test =
                when (airbyteProtocolType) {
                    AirbyteProtocolType.STRING -> "text"
                    AirbyteProtocolType.NUMBER -> "decimal"
                    AirbyteProtocolType.INTEGER -> "bigint"
                    AirbyteProtocolType.BOOLEAN -> "bit"
                    AirbyteProtocolType.TIMESTAMP_WITH_TIMEZONE -> "varchar"
                    AirbyteProtocolType.TIMESTAMP_WITHOUT_TIMEZONE -> "datetime"
                    AirbyteProtocolType.TIME_WITH_TIMEZONE -> "varchar"
                    AirbyteProtocolType.TIME_WITHOUT_TIMEZONE -> "time"
                    AirbyteProtocolType.DATE -> "date"
                    AirbyteProtocolType.UNKNOWN -> "json"
                }
            return test
        }
    }
}
