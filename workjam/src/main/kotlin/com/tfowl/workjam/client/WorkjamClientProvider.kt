package com.tfowl.workjam.client

import com.tfowl.workjam.client.model.WorkjamUser
import com.tfowl.workjam.client.model.serialisers.InstantSerialiser
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.time.format.DateTimeFormatter
import java.util.*

private const val ORIGIN_URL = "https://app.workjam.com"
private const val REFERRER_URL = "https://app.workjam.com/"
private val ACCEPTED_LANGUAGE = Locale.ENGLISH

private fun parseJwtUserId(token: String): Long {
    val parts = token.removePrefix("Bearer ").split(".")
    require(parts.size == 3) { "Invalid JWT token format" }
    val payload = String(Base64.getUrlDecoder().decode(parts[1]))
    val subMatch = """"sub"\s*:\s*"(\d+)"""".toRegex().find(payload)
    requireNotNull(subMatch) { "Could not find 'sub' claim in JWT" }
    return subMatch.groupValues[1].toLong()
}

class WorkjamClientProvider(
    private val credentials: WorkjamCredentialStorage,
    private val httpEngineProvider: HttpEngineProvider = DefaultHttpEngineProvider(),
) {
    private val client = HttpClient(httpEngineProvider.provide()) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
        }
        install(DefaultRequest) {
            header(HttpHeaders.AcceptLanguage, ACCEPTED_LANGUAGE)
            header(HttpHeaders.Origin, ORIGIN_URL)
            header(HttpHeaders.Referrer, REFERRER_URL)
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 5)
            exponentialDelay()
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                serializersModule = SerializersModule {
                    contextual(InstantSerialiser(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS[XX][XXX][ZZZ][OOOO]")))
                }
            })
        }
        install(Logging) {
            level = LogLevel.INFO
            logger = Logger.DEFAULT
        }
        // Hehe sneaky
        install(UserAgent) {
            agent =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.75 Safari/537.36"
        }
    }

    private fun createUserFromToken(token: String): WorkjamUser {
        val userId = parseJwtUserId(token)
        return WorkjamUser(
            token = token,
            userId = userId,
            firstLogin = false,
            hasEmployers = true,
            userRole = "EMPLOYEE",
            correlationId = "",
            employers = emptyList()
        )
    }

    private suspend fun retrieveAuthenticatedUser(
        ref: String,
        tokenOverride: String?
    ): WorkjamUser {
        val token = requireNotNull(tokenOverride ?: credentials.retrieve(ref)) {
            "No token available for user reference id: $ref"
        }

        return createUserFromToken(token)
    }

    suspend fun createClient(ref: String, tokenOverride: String? = null): WorkjamClient {
        val auth = retrieveAuthenticatedUser(ref, tokenOverride)

        return DefaultWorkjamClient(auth, client, httpEngineProvider.defaultUrlBuilder().build())
    }

    companion object {
        fun create(
            credentialsStorage: WorkjamCredentialStorage,
            httpEngineProvider: HttpEngineProvider = DefaultHttpEngineProvider()
        ): WorkjamClientProvider {
            return WorkjamClientProvider(credentialsStorage, httpEngineProvider)
        }
    }
}