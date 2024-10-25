package io.airbyte.integrations.destination.teradata.typing_deduping

import io.airbyte.cdk.integrations.destination.StandardNameTransformer
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcSqlGenerator
import io.airbyte.integrations.base.destination.typing_deduping.AirbyteType
import io.airbyte.integrations.base.destination.typing_deduping.ColumnId
import io.airbyte.integrations.base.destination.typing_deduping.Sql
import io.airbyte.integrations.base.destination.typing_deduping.Sql.Companion.of
import java.util.*
import org.jooq.Condition
import org.jooq.DataType
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.impl.DefaultDataType
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class TeradataSqlGenerator(
) : JdbcSqlGenerator(namingTransformer = StandardNameTransformer()) {



    override fun createSchema(schema: String): Sql {
        return of(
            String.format(
                "CREATE DATABASE \"%s\" AS PERMANENT = 120e6, SPOOL = 120e6;",
                schema,
            ),
        )
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
        TODO("Not yet implemented")
    }

    override fun cdcDeletedAtNotNullCondition(): Condition {
        TODO("Not yet implemented")
    }

    override fun extractRawDataFields(
        columns: LinkedHashMap<ColumnId, AirbyteType>,
        useExpensiveSaferCasting: Boolean
    ): MutableList<Field<*>> {
        TODO("Not yet implemented")
    }

    override fun getRowNumber(
        primaryKey: List<ColumnId>,
        cursorField: Optional<ColumnId>
    ): Field<Int> {
        TODO("Not yet implemented")
    }

    companion object {

        private val LOGGER: Logger = LoggerFactory.getLogger(TeradataSqlGenerator::class.java)
        val JSON_TYPE: DefaultDataType<Any> =
            DefaultDataType(
                null,
                Any::class.java,
                "json",
            )
    }

}
