package io.airbyte.integrations.destination.teradata.typing_deduping

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.integrations.base.AirbyteTraceMessageUtility
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcDestinationHandler
import io.airbyte.commons.json.Jsons
import io.airbyte.integrations.base.destination.typing_deduping.AirbyteProtocolType
import io.airbyte.integrations.base.destination.typing_deduping.AirbyteType
import io.airbyte.integrations.base.destination.typing_deduping.Array
import io.airbyte.integrations.base.destination.typing_deduping.Sql
import io.airbyte.integrations.base.destination.typing_deduping.Struct
import io.airbyte.integrations.base.destination.typing_deduping.Union
import io.airbyte.integrations.base.destination.typing_deduping.UnsupportedOneOf
import io.airbyte.integrations.base.destination.typing_deduping.migrators.MinimumDestinationState
import io.airbyte.protocol.models.v0.AirbyteStreamNameNamespacePair
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.*
import org.jooq.SQLDialect
import org.jooq.conf.ParamType
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.quotedName
import org.jooq.impl.SQLDataType
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class TeradataDestinationHandler(
    jdbcDatabase: JdbcDatabase,
    rawTableDatabaseName: String
) :
    JdbcDestinationHandler<MinimumDestinationState>(
        null,
        jdbcDatabase,
        rawTableDatabaseName,
        SQLDialect.DEFAULT
    ){
    override fun toDestinationState(json: JsonNode): MinimumDestinationState =
        MinimumDestinationState.Impl(
            json.hasNonNull("needsSoftReset") && json["needsSoftReset"].asBoolean(),
        )



    override fun toJdbcTypeName(airbyteType: AirbyteType): String {
        LOGGER.info("Satish : toJdbcTypeName : {}" , airbyteType.typeName)
        val test =
            if (airbyteType is AirbyteProtocolType) {
                Companion.toJdbcTypeName(airbyteType)
            } else {
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

            try {
                jdbcDatabase.execute(sqlStatement)
            } catch (e: SQLException) {
                if (e.message!!.contains("already exists")) {
                    LOGGER.warn("Table already exists: {}", sqlStatement)
                } else {
                    AirbyteTraceMessageUtility.emitTransientErrorTrace(
                        e,
                        "Connector failed while creating table ",
                    )
                    throw RuntimeException(e)
                }
            }

            // Fetch all records from it.
            return jdbcDatabase
                .queryJsons(
                    dslContext.select(
                        field(quotedName(DESTINATION_STATE_TABLE_COLUMN_NAME)),
                        field(quotedName(DESTINATION_STATE_TABLE_COLUMN_NAMESPACE)),
                        field(quotedName(DESTINATION_STATE_TABLE_COLUMN_STATE)),
                        field(quotedName(DESTINATION_STATE_TABLE_COLUMN_UPDATED_AT)),
                    ).from(quotedName(rawTableNamespace, DESTINATION_STATE_TABLE_NAME))
                        .getSQL(),
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
        } catch (e: Exception) {
            LOGGER.warn("Failed to retrieve destination states", e)
            return emptyMap()
        }
    }

    override fun execute(sql: Sql) {
        val transactions: List<List<String>> = sql.transactions
        for (transaction in transactions) {
            try {
                jdbcDatabase.executeWithinTransaction(transaction)
            } catch (e: SQLException) {
                if (e.message!!.contains("with the specified name already exists")) {
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
