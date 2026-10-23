package io.github.basekt.data

import io.github.basekt.schema.Resource

/**
 * An optional [DataSource] capability: counting the records that match a filter without loading them. Callers check
 * for it with `source as? Counting` and do without a total when a source doesn't offer it.
 *
 * Optional capabilities are separate interfaces like this one, never [DataSource] methods that throw by default.
 */
public interface Counting {
    /**
     * Returns how many records of [resource] match [filter], or all of them when it is `null`. Throws
     * [InvalidQueryException] for the same reasons [DataSource.list] does.
     */
    public suspend fun count(resource: Resource, filter: Filter? = null): Long
}
