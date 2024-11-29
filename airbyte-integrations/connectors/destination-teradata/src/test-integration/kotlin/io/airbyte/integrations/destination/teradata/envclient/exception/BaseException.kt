/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.destination.teradata.envclient.exception

open class BaseException : RuntimeException {
    private val statusCode: Int

    private val body: String

    private val reason: String?

    constructor(statusCode: Int, body: String) : super(body) {
        this.statusCode = statusCode
        this.body = body
        this.reason = null
    }

    constructor(statusCode: Int, body: String, reason: String?) : super(body) {
        this.statusCode = statusCode
        this.body = body
        this.reason = reason
    }
}
