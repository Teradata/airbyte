/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.destination.teradata.envclient.exception

class Error4xxException : BaseException {
    constructor(
        statusCode: Int,
        body: String?,
        reason: String?
    ) : super(
        statusCode,
        body!!,
        reason,
    )

    constructor(statusCode: Int, body: String?) : super(statusCode, body!!)
}
