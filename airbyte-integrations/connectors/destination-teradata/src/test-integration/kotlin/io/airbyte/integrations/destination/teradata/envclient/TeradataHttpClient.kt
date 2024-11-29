/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.destination.teradata.envclient

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import io.airbyte.integrations.destination.teradata.envclient.dto.CreateEnvironmentRequest
import io.airbyte.integrations.destination.teradata.envclient.dto.DeleteEnvironmentRequest
import io.airbyte.integrations.destination.teradata.envclient.dto.EnvironmentRequest
import io.airbyte.integrations.destination.teradata.envclient.dto.EnvironmentResponse
import io.airbyte.integrations.destination.teradata.envclient.dto.GetEnvironmentRequest
import io.airbyte.integrations.destination.teradata.envclient.exception.BaseException
import io.airbyte.integrations.destination.teradata.envclient.exception.Error4xxException
import io.airbyte.integrations.destination.teradata.envclient.exception.Error5xxException
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

class TeradataHttpClient(private val httpClient: HttpClient, private val baseUrl: String) {
    private val objectMapper: ObjectMapper =
        JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS, false)
            .build()

    constructor(
        baseUrl: String
    ) : this(
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
        baseUrl,
    )

    // Creating an environment is a blocking operation by default, and it takes ~1.5min to finish
    fun createEnvironment(
        createEnvironmentRequest: CreateEnvironmentRequest?,
        token: String
    ): CompletableFuture<EnvironmentResponse> {
        val requestBody = handleCheckedException {
            objectMapper.writeValueAsString(
                createEnvironmentRequest,
            )
        }

        val httpRequest =
            HttpRequest.newBuilder(
                    URI.create(
                        "$baseUrl/environments",
                    ),
                )
                .headers(
                    Headers.AUTHORIZATION,
                    Headers.BEARER + token,
                    Headers.CONTENT_TYPE,
                    Headers.APPLICATION_JSON,
                )
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).thenApply {
            httpResponse: HttpResponse<String> ->
            handleHttpResponse(
                httpResponse,
                object : TypeReference<EnvironmentResponse>() {},
            )
        }
    }

    fun getEnvironment(
        getEnvironmentRequest: GetEnvironmentRequest,
        token: String
    ): EnvironmentResponse {
        val httpRequest =
            HttpRequest.newBuilder(
                    URI.create(
                        baseUrl + "/environments/" + getEnvironmentRequest.name,
                    ),
                )
                .headers(Headers.AUTHORIZATION, Headers.BEARER + token)
                .GET()
                .build()

        val httpResponse = handleCheckedException {
            httpClient.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        return handleHttpResponse(
            httpResponse,
            object : TypeReference<EnvironmentResponse>() {},
        )
    }

    // Deleting an environment is a blocking operation by default, and it takes ~1.5min to finish
    fun deleteEnvironment(
        deleteEnvironmentRequest: DeleteEnvironmentRequest,
        token: String
    ): CompletableFuture<Void> {
        val httpRequest =
            HttpRequest.newBuilder(
                    URI.create(
                        baseUrl + "/environments/" + deleteEnvironmentRequest.name,
                    ),
                )
                .headers(Headers.AUTHORIZATION, Headers.BEARER + token)
                .DELETE()
                .build()

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).thenApply {
            httpResponse: HttpResponse<String> ->
            handleHttpResponse(
                httpResponse,
                object : TypeReference<Void>() {},
            )
        }
    }

    fun startEnvironment(
        environmentRequest: EnvironmentRequest,
        token: String
    ): CompletableFuture<Void> {
        val requestBody = handleCheckedException {
            objectMapper.writeValueAsString(
                environmentRequest.request,
            )
        }
        return getVoidCompletableFuture(environmentRequest.name, token, requestBody)
    }

    fun stopEnvironment(
        environmentRequest: EnvironmentRequest,
        token: String
    ): CompletableFuture<Void> {
        val requestBody = handleCheckedException {
            objectMapper.writeValueAsString(
                environmentRequest.request,
            )
        }
        return getVoidCompletableFuture(environmentRequest.name, token, requestBody)
    }

    private fun getVoidCompletableFuture(
        name: String,
        token: String,
        jsonPayLoadString: String
    ): CompletableFuture<Void> {
        val publisher = HttpRequest.BodyPublishers.ofString(jsonPayLoadString)
        val httpRequest =
            HttpRequest.newBuilder(
                    URI.create(
                        "$baseUrl/environments/$name",
                    ),
                )
                .headers(
                    Headers.AUTHORIZATION,
                    Headers.BEARER + token,
                    Headers.CONTENT_TYPE,
                    Headers.APPLICATION_JSON,
                )
                .method("PATCH", publisher)
                .build()
        val httpResponse =
            handleCheckedException<HttpResponse<String>> {
                httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
        return handleHttpResponse(
            httpResponse,
            object : TypeReference<CompletableFuture<Void>>() {},
        )
    }

    private fun <T> handleHttpResponse(
        httpResponse: HttpResponse<String>,
        typeReference: TypeReference<T>
    ): T {
        val body = httpResponse.body()
        if (httpResponse.statusCode() in 200..299) {
            return handleCheckedException {
                if (typeReference.type.typeName == Void::class.java.typeName) {
                    return@handleCheckedException null
                } else {
                    return@handleCheckedException objectMapper.readValue<T>(body, typeReference)
                }
            }
        } else if (httpResponse.statusCode() in 400..499) {
            throw Error4xxException(httpResponse.statusCode(), body)
        } else if (httpResponse.statusCode() in 500..599) {
            throw Error5xxException(httpResponse.statusCode(), body)
        } else {
            throw BaseException(httpResponse.statusCode(), body)
        }
    }

    private fun <T> handleCheckedException(checkedSupplier: CheckedSupplier<T?>): T {
        try {
            return checkedSupplier.get()!!
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException(e)
        }
    }

    private fun interface CheckedSupplier<T> {
        @Throws(IOException::class, InterruptedException::class) fun get(): T
    }
}
