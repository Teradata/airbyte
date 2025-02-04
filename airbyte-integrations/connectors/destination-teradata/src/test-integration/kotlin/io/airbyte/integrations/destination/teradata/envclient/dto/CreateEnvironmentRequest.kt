package io.airbyte.integrations.destination.teradata.envclient.dto

data class CreateEnvironmentRequest(val name: String, val region: String, val password: String)
