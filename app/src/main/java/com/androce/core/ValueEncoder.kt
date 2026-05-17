package com.androce.core

import com.androce.model.ValueType
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ValueEncoder {

    private val WILDCARD_BYTE: Byte = 0xCC.toByte()

    /**
     * For fuzzy float search - holds the range when searching for whole numbers.
     * e.g., input "11" -> range 11.0f to 12.0f (finds 11.0, 11.5, 11.99, etc.)
     */
    data class FloatRange(val min: Float, val max: Float)

    /**
     * Encode a user-entered string into a byte pattern for scanning.
     * Returns Pair(pattern, wildcard byte) — wildcard is non-null only for BYTE_ARRAY.
     */
    fun encodeSearchValue(
        input: String,
        type: ValueType,
        xorKey: Long = 0L
    ): Pair<ByteArray, Byte?> {
        return when (type) {
            ValueType.BYTE1 -> {
                val v = input.trim().toByte()
                Pair(byteArrayOf(v), null)
            }
            ValueType.BYTE2 -> {
                val v = input.trim().toShort()
                Pair(shortToBytes(v), null)
            }
            ValueType.BYTE4 -> {
                val v = input.trim().toInt()
                Pair(intToBytes(v), null)
            }
            ValueType.BYTE8 -> {
                val v = input.trim().toLong()
                Pair(longToBytes(v), null)
            }
            ValueType.FLOAT -> {
                val trimmed = input.trim()
                val v = trimmed.toFloat()
                // Check if input is a whole number (no decimal point and no scientific notation)
                // If so, we'll use range search: e.g., "11" -> search 11.0 to 12.0
                val isWholeNumber = !trimmed.contains(".") && 
                                    !trimmed.contains("e") && 
                                    !trimmed.contains("E") &&
                                    v == v.toInt().toFloat()
                if (isWholeNumber) {
                    // Return empty pattern as marker - ScanViewModel will handle range search
                    Pair(ByteArray(0), null)
                } else {
                    Pair(intToBytes(java.lang.Float.floatToIntBits(v)), null)
                }
            }
            ValueType.DOUBLE -> {
                val trimmed = input.trim()
                val v = trimmed.toDouble()
                // Check if input is a whole number (no decimal point and no scientific notation)
                // If so, we'll use range search: e.g., "11" -> search 11.0 to 12.0
                val isWholeNumber = !trimmed.contains(".") && 
                                    !trimmed.contains("e") && 
                                    !trimmed.contains("E") &&
                                    v == v.toInt().toDouble()
                if (isWholeNumber) {
                    // Return empty pattern as marker - ScanViewModel will handle range search
                    Pair(ByteArray(0), null)
                } else {
                    Pair(longToBytes(java.lang.Double.doubleToLongBits(v)), null)
                }
            }
            ValueType.STRING -> Pair(input.toByteArray(Charsets.UTF_8), null)
            ValueType.BYTE_ARRAY -> {
                val tokens = input.trim().split("\\s+".toRegex())
                val pattern = tokens.map {
                    if (it == "??") WILDCARD_BYTE
                    else it.toInt(16).toByte()
                }.toByteArray()
                Pair(pattern, WILDCARD_BYTE)
            }
            ValueType.XOR4 -> {
                val v = input.trim().toInt() xor xorKey.toInt()
                Pair(intToBytes(v), null)
            }
            ValueType.XOR8 -> {
                val v = input.trim().toLong() xor xorKey
                Pair(longToBytes(v), null)
            }
            ValueType.ALL,
            ValueType.ALL_INTEGER,
            ValueType.ALL_FLOAT,
            ValueType.ALL_NUMERIC -> Pair(ByteArray(0), null)
        }
    }

    /**
     * Encode a value string into bytes for writing to memory.
     */
    fun encodeWriteValue(input: String, type: ValueType, xorKey: Long = 0L): ByteArray? {
        return try {
            when (type) {
                ValueType.BYTE1 -> byteArrayOf(input.trim().toByte())
                ValueType.BYTE2 -> shortToBytes(input.trim().toShort())
                ValueType.BYTE4 -> intToBytes(input.trim().toInt())
                ValueType.BYTE8 -> longToBytes(input.trim().toLong())
                ValueType.FLOAT -> intToBytes(java.lang.Float.floatToIntBits(input.trim().toFloat()))
                ValueType.DOUBLE -> longToBytes(java.lang.Double.doubleToLongBits(input.trim().toDouble()))
                ValueType.STRING -> input.toByteArray(Charsets.UTF_8)
                ValueType.BYTE_ARRAY -> {
                    input.trim().split("\\s+".toRegex())
                        .filter { it != "??" }
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                }
                ValueType.XOR4 -> intToBytes(input.trim().toInt() xor xorKey.toInt())
                ValueType.XOR8 -> longToBytes(input.trim().toLong() xor xorKey)
                ValueType.ALL,
                ValueType.ALL_INTEGER,
                ValueType.ALL_FLOAT,
                ValueType.ALL_NUMERIC -> ByteArray(0)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if float input should use fuzzy/range search.
     * Returns FloatRange if input is a whole number (e.g., "11" -> 11.0f to 12.0f),
     * null if input has decimals (e.g., "11.5") - use exact search.
     */
    fun getFloatRangeIfWholeNumber(input: String): FloatRange? {
        val trimmed = input.trim()
        // Must not contain decimal point or scientific notation
        if (trimmed.contains(".") || trimmed.contains("e") || trimmed.contains("E")) {
            return null
        }
        val v = trimmed.toFloatOrNull() ?: return null
        // Check if it's a whole number
        if (v != v.toInt().toFloat()) return null
        val intVal = v.toInt()
        return FloatRange(intVal.toFloat(), (intVal + 1).toFloat())
    }

    data class DoubleRange(val min: Double, val max: Double)

    /**
     * Check if double input should use fuzzy/range search.
     * Returns DoubleRange if input is a whole number (e.g., "11" -> 11.0 to 12.0),
     * null if input has decimals (e.g., "11.5") - use exact search.
     */
    fun getDoubleRangeIfWholeNumber(input: String): DoubleRange? {
        val trimmed = input.trim()
        // Must not contain decimal point or scientific notation
        if (trimmed.contains(".") || trimmed.contains("e") || trimmed.contains("E")) {
            return null
        }
        val v = trimmed.toDoubleOrNull() ?: return null
        // Check if it's a whole number
        if (v != v.toInt().toDouble()) return null
        val intVal = v.toInt()
        return DoubleRange(intVal.toDouble(), (intVal + 1).toDouble())
    }

    fun intToBytes(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    fun longToBytes(v: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    fun shortToBytes(v: Short): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
}
