package io.airbyte.integrations.destination.teradata.envclient.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class EnvironmentResponse(
    @JsonProperty("state") val state: State,
    @JsonProperty("region") val region: String,
    // Use for subsequent environment operations i.e GET, DELETE, etc
    @JsonProperty("name") val name: String,
    // Use for connecting with JDBC driver
    @JsonProperty("ip") val ip: String,
    @JsonProperty("dnsName") val dnsName: String,
    @JsonProperty("owner") val owner: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("services") val services: List<Service>
) {
    data class Service(
        @JsonProperty("credentials") val credentials: List<Credential>,
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("state") val state: State
    )

    data class Credential(
        @JsonProperty("name") val name: String,
        @JsonProperty("value") val value: String
    )

    enum class State {
        PROVISIONING,
        INITIALIZING,
        RUNNING,
        STARTING,
        STOPPING,
        STOPPED,
        TERMINATING,
        TERMINATED,
        REPAIRING
    }
}
