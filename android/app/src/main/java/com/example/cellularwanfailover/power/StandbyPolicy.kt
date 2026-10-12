package com.example.cellularwanfailover.power

/** How the phone offers its cellular connection to its gateway (see PROTOCOL.md, standby). */
enum class StandbyMode(val storageValue: String) {
    /** Cold standby: cellular and relay only start when the gateway detects an outage */
    ON_DEMAND("on_demand"),
    /** Hot standby: the gateway keeps the tunnel up, so the router sees its backup WAN online */
    ALWAYS("always"),
    /** Hot while plugged in, on demand on battery */
    WHILE_PLUGGED("while_plugged");

    companion object {
        fun fromStorage(value: String?): StandbyMode = entries.firstOrNull { it.storageValue == value } ?: ON_DEMAND
    }
}

object StandbyPolicy {
    /** Back to cold this long after unplugging, so a loose cable or a quick move doesn't flap the tunnel */
    const val UNPLUG_GRACE_MILLIS = 2 * 60_000L

    /** [unpluggedForMillis]: time since the phone was unplugged, null while it is plugged in. */
    fun isHot(mode: StandbyMode, unpluggedForMillis: Long?): Boolean = when (mode) {
        StandbyMode.ON_DEMAND -> false
        StandbyMode.ALWAYS -> true
        StandbyMode.WHILE_PLUGGED -> unpluggedForMillis == null || unpluggedForMillis < UNPLUG_GRACE_MILLIS
    }

    /** Delay until [isHot] changes by itself (the unplug grace ending), null if it won't. */
    fun millisUntilChange(mode: StandbyMode, unpluggedForMillis: Long?): Long? =
        if (mode == StandbyMode.WHILE_PLUGGED && unpluggedForMillis != null && unpluggedForMillis < UNPLUG_GRACE_MILLIS) {
            UNPLUG_GRACE_MILLIS - unpluggedForMillis
        } else {
            null
        }
}
