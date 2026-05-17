package com.androce.model

enum class ChangeDirection { NONE, UP, DOWN }

data class ScanResult(
    val address: Long,
    val valueType: ValueType,
    val currentBytes: ByteArray,
    val previousBytes: ByteArray = currentBytes.copyOf(),
    val frozen: Boolean = false,
    val selected: Boolean = false,
    val changeDirection: ChangeDirection = ChangeDirection.NONE,
    val deltaDisplay: String = ""
) {
    val addressHex: String = "0x%X".format(address)
    private var cachedDisplayValue: String? = null

    fun displayValue(): String {
        cachedDisplayValue?.let { return it }
        val value = computeDisplayValue()
        cachedDisplayValue = value
        return value
    }

    private fun computeDisplayValue(): String = when (valueType) {
        ValueType.BYTE1 -> currentBytes.getOrElse(0) { 0 }.toString()
        ValueType.BYTE2 -> bytesToShort(currentBytes).toString()
        ValueType.BYTE4 -> bytesToInt(currentBytes).toString()
        ValueType.BYTE8 -> bytesToLong(currentBytes).toString()
        ValueType.FLOAT -> java.lang.Float.intBitsToFloat(bytesToInt(currentBytes)).toString()
        ValueType.DOUBLE -> java.lang.Double.longBitsToDouble(bytesToLong(currentBytes)).toString()
        ValueType.STRING -> {
            val utf8 = String(currentBytes, Charsets.UTF_8)
            val utf16 = String(currentBytes, Charsets.UTF_16LE)
            // Prefer UTF-8 if valid (no replacement chars), else UTF-16LE
            if (utf8.contains('\uFFFD')) utf16 else utf8
        }
        ValueType.BYTE_ARRAY -> currentBytes.joinToString(" ") { "%02X".format(it) }
        ValueType.XOR4 -> bytesToInt(currentBytes).toString()
        ValueType.XOR8 -> bytesToLong(currentBytes).toString()
        ValueType.ALL_INTEGER -> "INT"
        ValueType.ALL_FLOAT -> "FLT"
        ValueType.ALL_NUMERIC, ValueType.ALL -> "ALL"
    }

    fun clearChange(): ScanResult = copy(
        changeDirection = ChangeDirection.NONE,
        deltaDisplay = ""
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanResult) return false
        return address == other.address
                && valueType == other.valueType
                && selected == other.selected
                && frozen == other.frozen
                && changeDirection == other.changeDirection
                && deltaDisplay == other.deltaDisplay
                && currentBytes.contentEquals(other.currentBytes)
                && previousBytes.contentEquals(other.previousBytes)
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + valueType.hashCode()
        result = 31 * result + selected.hashCode()
        result = 31 * result + frozen.hashCode()
        result = 31 * result + changeDirection.hashCode()
        result = 31 * result + deltaDisplay.hashCode()
        result = 31 * result + currentBytes.contentHashCode()
        result = 31 * result + previousBytes.contentHashCode()
        return result
    }
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
