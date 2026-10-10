package com.example.model

import android.content.ComponentCallbacks2

/**
 * 8.9: decides when the ~2.6 GB engine may be released under memory pressure.
 *
 * Releasing and then re-loading the engine is a multi-second, multi-GB CPU+IO spike,
 * so an ordinary trim must NOT tear it down (that load/unload thrash is what made the
 * phone hang). Only genuine critical pressure justifies a release; the next explicit
 * use reloads it. Pure and unit-testable.
 */
object MemoryTrimPolicy {

    /** True only under genuine critical pressure; ordinary/background trims keep the engine. */
    fun shouldReleaseEngine(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
}
