package io.airbyte.integrations.destination.teradata.envclient.dto

data class EnvironmentRequest(val name: String, val request: OperationRequest)
