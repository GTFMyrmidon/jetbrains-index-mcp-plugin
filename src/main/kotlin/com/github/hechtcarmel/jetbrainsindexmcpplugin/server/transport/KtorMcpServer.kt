package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpServerFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.net.BindException

/**
 * Embedded Ktor CIO server hosting the MCP endpoints.
 *
 * This class owns only what the MCP Kotlin SDK does not: the engine lifecycle, the loopback
 * origin/host guard with its CORS headers, and the IDE-facing start/stop/restart surface.
 * Protocol handling —
 * JSON-RPC framing, `initialize` and version negotiation, `tools/list`, `tools/call`, SSE
 * framing, session ids — belongs to the SDK.
 *
 * Endpoints (unchanged from the hand-written implementation):
 *
 * 1. Streamable HTTP, stateless (MCP 2025-03-26 and later) — **primary**
 *    - `POST   /index-mcp/streamable-http` → JSON-RPC request/response as JSON
 *    - `GET`/`DELETE` → 405 Method Not Allowed (with `Allow: POST` header)
 *
 * 2. Legacy HTTP+SSE (MCP 2024-11-05)
 *    - `GET  /index-mcp/sse` → opens the stream, emits `endpoint: /index-mcp?sessionId=…`
 *    - `POST /index-mcp?sessionId=…` → response delivered on the stream
 */
class KtorMcpServer(
    private val port: Int,
    private val host: String = McpConstants.DEFAULT_SERVER_HOST,
    private val serverFactory: McpServerFactory,
    private val legacySseTransports: LegacySseTransports,
    private val coroutineScope: CoroutineScope,
    private val onUnexpectedStop: (() -> Unit)? = null
) : Disposable {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile private var intentionallyStopped = false
    @Volatile private var engineRunning = false

    companion object {
        private val LOG = logger<KtorMcpServer>()
    }

    /** Result of attempting to start the server. */
    sealed class StartResult {
        data object Success : StartResult()
        data class PortInUse(val port: Int) : StartResult()
        data class Error(val message: String, val cause: Throwable? = null) : StartResult()
    }

    fun start(): StartResult {
        intentionallyStopped = false
        return try {
            val embeddedServer = embeddedServer(CIO, port = port, host = host) {
                configure()
            }
            embeddedServer.monitor.subscribe(ApplicationStarted) { engineRunning = true }
            embeddedServer.monitor.subscribe(ApplicationStopped) {
                engineRunning = false
                if (!intentionallyStopped) {
                    LOG.warn("MCP Server stopped unexpectedly on $host:$port")
                    onUnexpectedStop?.invoke()
                }
            }
            server = embeddedServer
            embeddedServer.start(wait = false)

            LOG.info("MCP Server started on http://$host:$port")
            StartResult.Success
        } catch (e: BindException) {
            LOG.warn("Port $port is already in use", e)
            StartResult.PortInUse(port)
        } catch (e: Exception) {
            if (e is CancellationException) {
                val cause = e.cause
                if (cause is BindException) {
                    LOG.warn("Failed to start server on $host:$port: ${cause.message}", cause)
                    return StartResult.Error("Failed to bind to $host:$port. ${cause.message}", cause)
                }
                throw e
            }
            LOG.error("Failed to start MCP server", e)
            StartResult.Error(e.message ?: "Unknown error", e)
        }
    }

    fun stop() {
        intentionallyStopped = true
        engineRunning = false
        try {
            server?.stop(1000, 2000)
            server = null
            LOG.info("MCP Server stopped")
        } catch (e: Exception) {
            LOG.warn("Error stopping MCP server", e)
        }
    }

    /**
     * Whether the server is currently running.
     *
     * Tracks the actual engine lifecycle via ApplicationStarted/ApplicationStopped: `server !=
     * null` alone is insufficient, because the Ktor engine can die internally while the
     * reference stays set — which is exactly what the watchdog exists to catch.
     */
    fun isRunning(): Boolean = server != null && engineRunning

    override fun dispose() = stop()

    private fun Application.configure() {
        // kotlin-sdk 0.10.0's Ktor helpers install SSE but not ContentNegotiation, while its
        // transports reply with `call.respond(<serializable>)`. With no converter registered Ktor
        // answers every MCP request with an empty 406. McpJson is the exact configuration the SDK
        // uses internally (explicitNulls = false, encodeDefaults = true) — anything else changes
        // the bytes on the wire.
        install(ContentNegotiation) { json(McpJson) }

        // Covers every MCP endpoint: they all live under /index-mcp. Installed before the SDK
        // routes so a rejected request never reaches a handler.
        installMcpOriginGuard(
            pathPrefix = McpConstants.MCP_ENDPOINT_PATH,
            bindHost = host
        )

        // SSE plugin installed at Application level so both Streamable HTTP and legacy SSE
        // routes can utilize SSE capabilities.
        install(SSE)

        routing {
            streamableHttpRoutes(serverFactory, coroutineScope)
            legacySseRoutes(serverFactory, legacySseTransports, coroutineScope)
        }
    }
}

internal fun Route.streamableHttpRoutes(
    factory: McpServerFactory,
    scope: CoroutineScope
) {
    route(McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH) {
        post {
            handleStatelessStreamableHttpPost(factory, scope)
        }
        get {
            call.response.header(HttpHeaders.Allow, "POST")
            call.respondText("Method Not Allowed", status = HttpStatusCode.MethodNotAllowed)
        }
        delete {
            call.response.header(HttpHeaders.Allow, "POST")
            call.respondText("Method Not Allowed", status = HttpStatusCode.MethodNotAllowed)
        }
    }
}

private suspend fun RoutingContext.handleStatelessStreamableHttpPost(
    factory: McpServerFactory,
    scope: CoroutineScope
) {
    val transport = StreamableHttpServerTransport(
        StreamableHttpServerTransport.Configuration(
            enableDnsRebindingProtection = false,
            enableJsonResponse = true
        )
    ).also { it.setSessionIdGenerator(null) }

    val server = factory.newServer()
    // Stateless mode creates a transport and a session per POST and never closes
    // them. Without this the Server's session map and its notification scope grow
    // for the lifetime of the IDE.
    call.coroutineContext.job.invokeOnCompletion {
        scope.launch { server.close() }
    }
    server.createSession(transport)

    val normalizedCall = call.withNormalizedMcpAccept()
    transport.handleRequest(null, normalizedCall)
}

/**
 * Normalizes the `Accept` header for MCP stateless JSON-RPC handling.
 *
 * kotlin-sdk 0.10.0's [StreamableHttpServerTransport] requires the `Accept` header to contain
 * *both* `application/json` and `text/event-stream`. Real clients like Cursor, VS Code, and
 * standard HTTP clients often send only `Accept: application/json`, wildcard `Accept`, or omit the
 * header entirely.
 *
 * This wrapper normalizes headers containing acceptable JSON/wildcard/stream indicators to
 * `"application/json, text/event-stream"`, allowing the SDK transport and Ktor ContentNegotiation
 * to serialize the JSON-RPC response without returning 406 Not Acceptable.
 */
internal fun ApplicationCall.withNormalizedMcpAccept(): ApplicationCall {
    val originalAccept = request.headers[HttpHeaders.Accept]
    val normalizedAccept = normalizeMcpAccept(originalAccept)
    if (normalizedAccept == originalAccept) return this

    return object : ApplicationCall by this {
        override val request: ApplicationRequest = object : ApplicationRequest by this@withNormalizedMcpAccept.request {
            override val headers: Headers = object : Headers by this@withNormalizedMcpAccept.request.headers {
                override fun get(name: String): String? {
                    if (name.equals(HttpHeaders.Accept, ignoreCase = true)) {
                        return normalizedAccept
                    }
                    return this@withNormalizedMcpAccept.request.headers[name]
                }

                override fun getAll(name: String): List<String>? {
                    if (name.equals(HttpHeaders.Accept, ignoreCase = true)) {
                        return listOf(normalizedAccept)
                    }
                    return this@withNormalizedMcpAccept.request.headers.getAll(name)
                }
            }
        }
    }
}

internal fun normalizeMcpAccept(acceptHeader: String?): String {
    if (acceptHeader.isNullOrBlank()) {
        return "application/json, text/event-stream"
    }
    val lower = acceptHeader.lowercase()
    val acceptsJson = lower.contains("application/json") || lower.contains("*/*") || lower.contains("application/*")
    val acceptsSse = lower.contains("text/event-stream")

    return when {
        acceptsJson && acceptsSse -> acceptHeader
        acceptsJson || acceptsSse -> "application/json, text/event-stream"
        else -> acceptHeader
    }
}

internal const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"
internal const val MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
