package com.hometv.tv.data

import java.util.concurrent.atomic.AtomicLong

/**
 * Detail refresh (prd3 §6/§7): launching the Player changes watch state (progress, the next part,
 * `next_episode`, watched marks), so [AppNavigator.play][com.hometv.tv.ui.navigation.AppNavigator.play]
 * bumps a generation. Each detail ViewModel remembers the generation of its last fetch and refetches
 * in place when it becomes visible again with an older one. A counter (not a flag like [HomeRefresh])
 * because several detail screens can sit on the back stack, and each must see the change once.
 */
class DetailRefresh {
    private val generation = AtomicLong(0)

    fun markStale() {
        generation.incrementAndGet()
    }

    fun generation(): Long = generation.get()
}
