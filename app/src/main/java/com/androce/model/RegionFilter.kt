package com.androce.model

/**
 * Restricts which readable memory regions are scanned.
 *
 * - [ALL]          : every readable user region (slow, exhaustive).
 * - [HEAP_STACK_ANON] : [heap], [stack], and anonymous regions only — default for typical CE workflow.
 * - [MODULE]       : only regions whose name contains [name] (case-insensitive substring).
 */
sealed class RegionFilter {
    object ALL : RegionFilter()
    object HEAP_STACK_ANON : RegionFilter()
    data class MODULE(val name: String) : RegionFilter()

    val label: String
        get() = when (this) {
            ALL -> "All"
            HEAP_STACK_ANON -> "Heap / Stack / Anon"
            is MODULE -> "Module: $name"
        }
}

fun MemoryRegion.matchesFilter(filter: RegionFilter): Boolean {
    val n = name.trim()
    return when (filter) {
        RegionFilter.ALL -> true
        RegionFilter.HEAP_STACK_ANON ->
            n.isEmpty() || n == "[heap]" || n == "[stack]" ||
                n == "[anon]" || n.startsWith("[anon:")
        is RegionFilter.MODULE -> n.contains(filter.name, ignoreCase = true)
    }
}
