package com.androce.model

enum class ValueTypeCategory(val label: String) {
    INTEGER("Integer"),
    FLOAT("Float"),
    TEXT("Text"),
    SPECIAL("Special")
}

enum class ValueType(val label: String, val byteSize: Int, val category: ValueTypeCategory, val description: String) {
    BYTE1("Byte",    1,  ValueTypeCategory.INTEGER, "8-bit  −128 to 127"),
    BYTE2("Short",   2,  ValueTypeCategory.INTEGER, "16-bit −32768 to 32767"),
    BYTE4("Int",     4,  ValueTypeCategory.INTEGER, "32-bit integer"),
    BYTE8("Long",    8,  ValueTypeCategory.INTEGER, "64-bit integer"),
    FLOAT("Float",   4,  ValueTypeCategory.FLOAT,   "32-bit decimal"),
    DOUBLE("Double", 8,  ValueTypeCategory.FLOAT,   "64-bit decimal"),
    STRING("String", -1, ValueTypeCategory.TEXT, "Search text (UTF-8 / UTF-16)"),
    BYTE_ARRAY("Byte Array", -1, ValueTypeCategory.SPECIAL, "Hex bytes with ?? wildcards"),
    XOR4("XOR Int",  4,  ValueTypeCategory.SPECIAL, "Int XOR with key"),
    XOR8("XOR Long", 8,  ValueTypeCategory.SPECIAL, "Long XOR with key"),
    ALL_INTEGER("All Integer", -1, ValueTypeCategory.INTEGER, "Search all integer types (1-8 bytes)"),
    ALL_FLOAT("All Float", -1, ValueTypeCategory.FLOAT, "Search all float types (Float+Double)"),
    ALL_NUMERIC("All Numeric", -1, ValueTypeCategory.SPECIAL, "Search all numeric types"),
    ALL("All Types", -1, ValueTypeCategory.SPECIAL, "Search all numeric types");

    val isVariableLength get() = byteSize == -1
}
