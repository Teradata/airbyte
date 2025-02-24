/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.teradata.typing_deduping

import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.integrations.destination.jdbc.JdbcGenerationHandler

/**
 * A class responsible for handling Teradata-specific generation operations. This class implements
 * the `JdbcGenerationHandler` interface and provides methods for interacting with the generation id
 * within Teradata tables.
 *
 * @constructor Initializes a new instance of `TeradataGenerationHandler`.
 */
class TeradataGenerationHandler() : JdbcGenerationHandler {
    /**
     * Retrieves the generation ID from a Teradata table.
     *
     * @param database The `JdbcDatabase` instance used to interact with the database.
     * @param namespace The namespace (schema) of the table.
     * @param name The name of the table.
     * @return A `Long?` representing the generation ID in the table, or `null` if no generation ID
     * is found.
     * ```
     *         This implementation returns `0` as a placeholder value.
     * ```
     */
    override fun getGenerationIdInTable(
        database: JdbcDatabase,
        namespace: String,
        name: String
    ): Long? {
        return 0
    }
}
