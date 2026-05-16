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
    STRING_UTF8("UTF-8",   -1, ValueTypeCategory.TEXT, "ASCII / UTF-8 string"),
    STRING_UTF16("UTF-16", -1, ValueTypeCategory.TEXT, "Unicode wide string"),
    BYTE_ARRAY("Byte Array", -1, ValueTypeCategory.SPECIAL, "Hex bytes with ?? wildcards"),
    XOR4("XOR Int",  4,  ValueTypeCategory.SPECIAL, "Int XOR with key"),
    XOR8("XOR Long", 8,  ValueTypeCategory.SPECIAL, "Long XOR with key"),
    ALL("All Types", -1, ValueTypeCategory.INTEGER, "Search all numeric types");

    val isVariableLength get() = byteSize == -1
}
