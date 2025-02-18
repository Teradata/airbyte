/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.teradata.typing_deduping

import com.fasterxml.jackson.databind.node.ObjectNode

class TeradataDisableTypingDedupingTest : AbstractTeradataTypingDedupingTest() {
    override fun getBaseConfig(): ObjectNode =
        super.getBaseConfig().put("disable_type_dedupe", true)
    override fun disableFinalTableComparison(): Boolean = true
}
