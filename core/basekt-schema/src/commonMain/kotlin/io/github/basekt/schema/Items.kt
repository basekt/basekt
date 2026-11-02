package io.github.basekt.schema

/**
 * Returns [record] with an id, from [newId], on every item of its [Field.itemIds] lists that came without one. An
 * item that has an id keeps it, so it stays the same item through edits and reorders.
 */
public fun Resource.withItemIds(record: Record, newId: () -> String): Record {
    val lists = fields.filter { it.itemIds && record[it.name] is Value.Items }
    if (lists.isEmpty()) return record
    val out = LinkedHashMap(record)
    for (field in lists) {
        val items = record[field.name] as Value.Items
        out[field.name] = Value.Items(
            items.values.map { item ->
                if (item is Value.Object && item.values[Resource.ID] == null) {
                    Value.Object(linkedMapOf<String, Value?>(Resource.ID to Value.Text(newId())) + item.values.filterKeys { it != Resource.ID })
                } else {
                    item
                }
            },
        )
    }
    return out
}

/** Whether a field holds a list or objects, which can't be compared, sorted on or totalled. */
public val Field.isStructured: Boolean get() = list || type == FieldType.OBJECT
