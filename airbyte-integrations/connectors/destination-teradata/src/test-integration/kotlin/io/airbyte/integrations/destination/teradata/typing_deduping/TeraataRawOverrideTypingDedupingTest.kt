/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.teradata.typing_deduping

import com.fasterxml.jackson.databind.node.ObjectNode

class TeraataRawOverrideTypingDedupingTest : AbstractTeradataTypingDedupingTest() {
    override fun getBaseConfig(): ObjectNode =
        super.getBaseConfig().put("raw_data_schema", "overridden_raw_dataset")
    override val rawSchema = "overridden_raw_dataset"
}
