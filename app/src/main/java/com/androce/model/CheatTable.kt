package com.androce.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent representation of a user's saved addresses ("cheat table").
 * Serialized as JSON using org.json (no extra deps).
 */
data class CheatTableEntry(
    val address: Long,
    val label: String,
    val valueType: ValueType,
    val frozen: Boolean,
    val frozenValueHex: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("address", address)
        put("label", label)
        put("valueType", valueType.name)
        put("frozen", frozen)
        if (frozenValueHex != null) put("frozenValueHex", frozenValueHex)
    }

    companion object {
        fun fromJson(obj: JSONObject): CheatTableEntry = CheatTableEntry(
            address = obj.getLong("address"),
            label = obj.optString("label", ""),
            valueType = runCatching { ValueType.valueOf(obj.getString("valueType")) }
                .getOrDefault(ValueType.BYTE4),
            frozen = obj.optBoolean("frozen", false),
            frozenValueHex = if (obj.has("frozenValueHex")) obj.getString("frozenValueHex") else null
        )
    }
}

data class CheatTable(
    val processName: String,
    val savedAt: Long,
    val entries: List<CheatTableEntry>
) {
    fun toJson(): String = JSONObject().apply {
        put("processName", processName)
        put("savedAt", savedAt)
        put("entries", JSONArray().also { arr ->
            entries.forEach { arr.put(it.toJson()) }
        })
    }.toString(2)

    companion object {
        fun fromJson(text: String): CheatTable {
            val obj = JSONObject(text)
            val arr = obj.getJSONArray("entries")
            val list = (0 until arr.length()).map { CheatTableEntry.fromJson(arr.getJSONObject(it)) }
            return CheatTable(
                processName = obj.optString("processName", ""),
                savedAt = obj.optLong("savedAt", 0L),
                entries = list
            )
        }
    }
}
