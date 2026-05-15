package com.androce.core

import com.androce.model.ValueType
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ValueEncoder {

    private val WILDCARD_BYTE: Byte = 0xCC.toByte()

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
                val v = input.trim().toFloat()
                Pair(intToBytes(java.lang.Float.floatToIntBits(v)), null)
            }
            ValueType.DOUBLE -> {
                val v = input.trim().toDouble()
                Pair(longToBytes(java.lang.Double.doubleToLongBits(v)), null)
            }
            ValueType.STRING_UTF8 -> Pair(input.toByteArray(Charsets.UTF_8), null)
            ValueType.STRING_UTF16 -> Pair(input.toByteArray(Charsets.UTF_16LE), null)
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
            ValueType.ALL -> Pair(ByteArray(0), null)
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
                ValueType.STRING_UTF8 -> input.toByteArray(Charsets.UTF_8)
                ValueType.STRING_UTF16 -> input.toByteArray(Charsets.UTF_16LE)
                ValueType.BYTE_ARRAY -> {
                    input.trim().split("\\s+".toRegex())
                        .filter { it != "??" }
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                }
                ValueType.XOR4 -> intToBytes(input.trim().toInt() xor xorKey.toInt())
                ValueType.XOR8 -> longToBytes(input.trim().toLong() xor xorKey)
                ValueType.ALL -> ByteArray(0)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun intToBytes(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    fun longToBytes(v: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    fun shortToBytes(v: Short): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
}
