package com.pitstop.domain

/**
 * Dedupe rules for the vehicle-alert and service-reminder notifications.
 * Pure: the notifiers persist what these return and post what they flag.
 */
object AlertRules {

    /**
     * New check-engine codes. The first time a vehicle is checked the active
     * set is recorded silently — an upgrade must not announce codes the user
     * has been living with as "new". After that, a code is notified once,
     * the first time it is ever seen active.
     */
    fun newDtcCodes(active: Set<String>, seen: Set<String>, seeded: Boolean): Pair<List<String>, Set<String>> {
        val normalized = active.mapTo(LinkedHashSet()) { it.trim().uppercase() }
        if (!seeded) return emptyList<String>() to (seen + normalized)
        val fresh = normalized.filter { it !in seen }
        return fresh to (seen + normalized)
    }

    /**
     * Reminder state changes worth a notification: a reminder that is now
     * due-soon or overdue and was last seen in a different state. The
     * returned map replaces the stored one, so a reminder that leaves the
     * endpoint's window (marked done, or not yet close) is forgotten and
     * will notify again when it next comes due.
     */
    fun reminderTransitions(
        current: Map<String, DueState>,
        lastSeen: Map<String, String>,
    ): Pair<List<String>, Map<String, String>> {
        val notify = current.filter { (id, state) ->
            state != DueState.Ok && lastSeen[id] != state.name
        }.keys.toList()
        return notify to current.mapValues { it.value.name }
    }
}
