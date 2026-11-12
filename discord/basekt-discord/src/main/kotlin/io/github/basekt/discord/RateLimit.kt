package io.github.basekt.discord

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** At most [max] commands per user in any [window]. */
public data class RateLimit(
    /** How many commands one user may run in a window. */
    public val max: Int = 5,
    /** How long a window lasts. */
    public val window: Duration = 10.seconds,
) {
    init {
        require(max >= 1) { "max must be at least 1." }
        require(window.isPositive()) { "window must be positive." }
    }
}

/** A sliding-window limiter, one window per user. It keeps its counts in memory and forgets idle users. */
internal class SlidingWindowLimiter(private val limit: RateLimit, private val clock: Clock) {
    private val hits = HashMap<String, ArrayDeque<Instant>>()
    private val lock = Mutex()

    /** Records a command by [userId] and returns `true`, or returns `false` when the user is over the limit. */
    suspend fun tryAcquire(userId: String): Boolean = lock.withLock {
        val now = clock.now()
        if (hits.size > SWEEP_AT) sweep(now)
        val window = hits.getOrPut(userId) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= limit.window) window.removeFirst()
        if (window.size >= limit.max) return@withLock false
        window.addLast(now)
        true
    }

    private fun sweep(now: Instant) {
        hits.entries.removeAll { (_, times) -> times.isEmpty() || now - times.last() >= limit.window }
    }

    private companion object {
        const val SWEEP_AT = 10_000
    }
}
