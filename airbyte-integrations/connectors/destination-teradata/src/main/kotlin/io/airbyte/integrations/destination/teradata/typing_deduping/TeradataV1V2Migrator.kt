package io.airbyte.integrations.destination.teradata.typing_deduping

import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.integrations.destination.StandardNameTransformer
import io.airbyte.cdk.integrations.destination.jdbc.TableDefinition
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcDestinationHandler
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcV1V2Migrator
import java.util.Optional
import lombok.SneakyThrows
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class TeradataV1V2Migrator(database: JdbcDatabase) :
    JdbcV1V2Migrator(StandardNameTransformer(), database, null) {

    @SneakyThrows
    @Throws(Exception::class)
    override fun getTableIfExists(
        namespace: String?,
        tableName: String?
    ): Optional<TableDefinition> {
        val handler = JdbcDestinationHandler.Companion.findExistingTable(
            database,
            namespace,
            null,
            tableName
        )
        LOGGER.info("TeradataV1V2Migrator - getTableIfExists - namespace - {} - tableName - {}, handler - {}", namespace, tableName, handler)
        return handler
    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(
            TeradataV1V2Migrator::class.java,
        )
    }

}
