package io.github.basekt.data

/**
 * An optional [DataSource] capability: named counters that hand out numbers without repeats or gaps, for the
 * `sequence` fields of a schema. Callers check for it with `source as? Sequencing`. A source that offers it offers
 * it on the [DataSource] its [DataSource.transaction] passes to the block too.
 *
 * The contract:
 * - The first value of a name is the `start` of that first call; each later call returns one more.
 * - A number taken inside a transaction belongs to it: a rollback gives it back, so the next one taken is the same.
 * - Concurrent callers never get the same number. A transaction that takes one may make others wait until it ends.
 */
public interface Sequencing {
    /** Returns the next number of the counter called [name], which starts at [start] the first time it is used. */
    public suspend fun nextValue(name: String, start: Long = 1): Long
}
