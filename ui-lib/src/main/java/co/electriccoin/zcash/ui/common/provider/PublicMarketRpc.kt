package co.electriccoin.zcash.ui.common.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class PublicMarketRpc(private val client: HttpClient, private val url: String) {
    suspend fun read(method: String, params: JsonElement): JsonElement =
        result(post(payload(1, method, params)).jsonObject, 1)

    suspend fun batch(calls: List<Pair<String, JsonElement>>): List<JsonElement> {
        val responses = post(JsonArray(calls.mapIndexed { id, (method, params) -> payload(id, method, params) }))
            .jsonArray
        val byId = responses.associateBy { it.jsonObject.field("id").toInt() }
        require(responses.size == calls.size && byId.keys == calls.indices.toSet())
        return calls.indices.map { result(byId.getValue(it).jsonObject, it) }
    }

    private suspend fun post(body: JsonElement): JsonElement = client.readMarketBody(url, MAX_BYTES) {
        method = HttpMethod.Post
        setBody(TextContent(body.toString(), ContentType.Application.Json))
    }

    private fun payload(id: Int, method: String, params: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }

    private fun result(response: JsonObject, id: Int): JsonElement {
        require(response.field("jsonrpc") == "2.0" && response.field("id").toInt() == id && "error" !in response)
        return response.getValue("result")
    }

    private companion object {
        const val MAX_BYTES = 32_768
    }
}
