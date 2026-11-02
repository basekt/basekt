package io.github.basekt.data

import io.github.basekt.schema.Schema
import io.github.basekt.schema.isStructured

/**
 * An optional [DataSource] capability: storing list fields (`list()`) and object lists (`objects(...)`) inside their
 * records, in order, and answering [Filter.Has] on them. BaseKt refuses to serve a schema with such fields from a
 * source without it.
 */
public interface ListFields

/** The `resource.field` names of the list and object fields in this schema. */
public fun Schema.structuredFields(): List<String> =
    resources.flatMap { resource -> resource.fields.filter { it.isStructured }.map { "${resource.name}.${it.name}" } }
