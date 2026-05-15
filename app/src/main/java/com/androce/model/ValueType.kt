package com.androce.model

enum class ValueType(val label: String, val byteSize: Int) {
    BYTE1("Byte (1)", 1),
    BYTE2("Short (2)", 2),
    BYTE4("Int (4)", 4),
    BYTE8("Long (8)", 8),
    FLOAT("Float (4)", 4),
    DOUBLE("Double (8)", 8),
    STRING_UTF8("String UTF-8", -1),
    STRING_UTF16("String UTF-16", -1),
    BYTE_ARRAY("Byte Array (hex)", -1),
    XOR4("XOR Int (4)", 4),
    XOR8("XOR Long (8)", 8);

    val isVariableLength get() = byteSize == -1
}
