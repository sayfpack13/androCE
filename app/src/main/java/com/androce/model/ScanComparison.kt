package com.androce.model

/**
 * Comparison operators for refined scans against previously-found results.
 * Operands (when needed) are encoded by [com.androce.core.ValueEncoder].
 */
enum class ScanComparison(val label: String, val symbol: String, val needsValue: Boolean) {
    EXACT          ("Exact value",   "=",  true),
    CHANGED        ("Changed",        "≠",  false),
    UNCHANGED      ("Unchanged",      "=",  false),
    INCREASED      ("Increased",      ">",  false),
    DECREASED      ("Decreased",      "<",  false),
    INCREASED_BY   ("Increased by",   "+",  true),
    DECREASED_BY   ("Decreased by",   "-",  true),
    BETWEEN        ("Between",        "≷",  true);

    companion object {
        val withoutValue get() = listOf(CHANGED, UNCHANGED, INCREASED, DECREASED)
    }
}
