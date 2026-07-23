package com.trubus.tams.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Every timestamp in this system, server- and client-generated, is WIB
 * (Asia/Jakarta, GMT+7) wall-clock time regardless of the device's own
 * timezone setting -- centralized here instead of copy-pasting the same
 * "Asia/Jakarta" + SimpleDateFormat construction across the codebase.
 *
 * [formatter] returns a new [SimpleDateFormat] instance per call rather than
 * a shared one: this is called from both Compose UI-thread and
 * Dispatchers.IO code, and SimpleDateFormat isn't thread-safe. (Contrast
 * MemberLocationService, which caches its own formatter because it's only
 * ever touched from one thread.)
 */
object WibTime {
    val ZONE: TimeZone = TimeZone.getTimeZone("Asia/Jakarta")

    fun formatter(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = ZONE }

    /** Today's WIB calendar date as "yyyy-MM-dd" (matches the backend's DATE(recorded_at) filter). */
    fun today(): String = formatter("yyyy-MM-dd").format(Date())

    /** Current WIB timestamp as "dd/MM HH:mm:ss", used for the "last sync" display. */
    fun nowShort(): String = formatter("dd/MM HH:mm:ss").format(Date())
}
