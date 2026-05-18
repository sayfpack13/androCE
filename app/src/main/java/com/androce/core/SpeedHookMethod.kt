package com.androce.core

/**
 * Must match [SpeedInjector] / native speedhook SHM hook-mode field (offset 4).
 */
enum class SpeedHookMethod(
    val id: Int,
    val title: String,
    val description: String,
    val risk: HookRisk
) {
    MONITOR(
        id = 0,
        title = "Inject only",
        description = "Loads the library without time hooks. Use to verify injection works.",
        risk = HookRisk.SAFE
    ),
    LIBC_INLINE(
        id = 1,
        title = "GOT — all app libs",
        description = "Hooks clock_gettime/gettimeofday PLT in most app native libraries.",
        risk = HookRisk.HIGH
    ),
    PLT_GAME(
        id = 2,
        title = "GOT — game engines",
        description = "Hooks Cocos/Unity-style libs only. Skips FMOD and media. Best for rhythm and platformer games.",
        risk = HookRisk.LOW
    ),
    PLT_UNIVERSAL(
        id = 3,
        title = "GOT — universal",
        description = "Hooks most app native libs (clock + gettimeofday). May affect ads/network code.",
        risk = HookRisk.MEDIUM
    ),
    PLT_CLOCK_ONLY(
        id = 4,
        title = "GOT — clock only",
        description = "Like universal but only clock_gettime.",
        risk = HookRisk.MEDIUM
    );

    companion object {
        val selectable: List<SpeedHookMethod> = entries

        fun fromId(id: Int): SpeedHookMethod =
            entries.find { it.id == id } ?: PLT_GAME
    }
}

enum class HookRisk {
    SAFE, LOW, MEDIUM, HIGH
}
