package com.hometv.tv.data

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Home refresh rules: the Player route (watch state changes), a watched toggle on a
 * detail screen and `ItemGone` mark Home stale; Home reloads when it becomes visible again.
 */
class HomeRefresh {
    private val stale = AtomicBoolean(false)

    fun markStale() = stale.set(true)

    fun consumeStale(): Boolean = stale.getAndSet(false)
}
