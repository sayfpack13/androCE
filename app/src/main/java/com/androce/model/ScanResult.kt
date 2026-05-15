package com.androce.model

data class ScanResult(
    val address: Long,
    val valueType: ValueType,
    var currentBytes: ByteArray,
    var previousBytes: ByteArray? = null,
    var frozen: Boolean = false,
    var selected: Boolean = false
) {
    val addressHex: String get() = "0x%X".format(address)

    fun displayValue(): String = when (valueType) {
        ValueType.BYTE1 -> currentBytes.getOrElse(0) { 0 }.toString()
        ValueType.BYTE2 -> bytesToShort(currentBytes).toString()
        ValueType.BYTE4 -> bytesToInt(currentBytes).toString()
        ValueType.BYTE8 -> bytesToLong(currentBytes).toString()
        ValueType.FLOAT -> java.lang.Float.intBitsToFloat(bytesToInt(currentBytes)).toString()
        ValueType.DOUBLE -> java.lang.Double.longBitsToDouble(bytesToLong(currentBytes)).toString()
        ValueType.STRING_UTF8 -> String(currentBytes, Charsets.UTF_8)
        ValueType.STRING_UTF16 -> String(currentBytes, Charsets.UTF_16LE)
        ValueType.BYTE_ARRAY -> currentBytes.joinToString(" ") { "%02X".format(it) }
        ValueType.XOR4 -> bytesToInt(currentBytes).toString()
        ValueType.XOR8 -> bytesToLong(currentBytes).toString()
        ValueType.ALL -> "ALL"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanResult) return false
        return address == other.address && valueType == other.valueType
    }

    override fun hashCode(): Int = 31 * address.hashCode() + valueType.hashCode()
}

fun bytesToShort(b: ByteArray): Short =
    ((b.getOrElse(1) { 0 }.toInt() and 0xFF shl 8) or (b.getOrElse(0) { 0 }.toInt() and 0xFF)).toShort()

fun bytesToInt(b: ByteArray): Int =
    (b.getOrElse(3) { 0 }.toInt() and 0xFF shl 24) or
    (b.getOrElse(2) { 0 }.toInt() and 0xFF shl 16) or
    (b.getOrElse(1) { 0 }.toInt() and 0xFF shl 8) or
    (b.getOrElse(0) { 0 }.toInt() and 0xFF)

fun bytesToLong(b: ByteArray): Long =
    (b.getOrElse(7) { 0 }.toLong() and 0xFF shl 56) or
    (b.getOrElse(6) { 0 }.toLong() and 0xFF shl 48) or
    (b.getOrElse(5) { 0 }.toLong() and 0xFF shl 40) or
    (b.getOrElse(4) { 0 }.toLong() and 0xFF shl 32) or
    (b.getOrElse(3) { 0 }.toLong() and 0xFF shl 24) or
    (b.getOrElse(2) { 0 }.toLong() and 0xFF shl 16) or
    (b.getOrElse(1) { 0 }.toLong() and 0xFF shl 8) or
    (b.getOrElse(0) { 0 }.toLong() and 0xFF)
