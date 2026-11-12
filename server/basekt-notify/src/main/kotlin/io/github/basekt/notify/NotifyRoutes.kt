package io.github.basekt.notify

import io.github.basekt.data.Comparison
import io.github.basekt.data.DataSource
import io.github.basekt.data.Filter
import io.github.basekt.data.Query
import io.github.basekt.schema.FieldType
import io.github.basekt.schema.Resource
import io.github.basekt.schema.SchemaBuilder
import io.github.basekt.schema.Value

/** The fields of a notify routes resource, as [notifyRoutes] declares them. */
public object NotifyFields {
    /** The event key, such as `"signup"`. Unique. */
    public const val EVENT: String = "event"

    /** Whether the event is sent. */
    public const val ENABLED: String = "enabled"

    /** The destination name, one of the sender's destinations. */
    public const val DESTINATION: String = "destination"

    /** The id of a role to mention, or unset. */
    public const val MENTION: String = "mention"
}

/**
 * Declares the routes resource that `install(BaseKtNotify)` reads, called [name]. Callers with one of [editors] may
 * read, create, update and delete routes, so staff set where each event goes in the admin panel; nobody else can.
 */
public fun SchemaBuilder.notifyRoutes(name: String = "notifyRoutes", editors: Set<String>) {
    require(editors.isNotEmpty()) { "notifyRoutes needs at least one editor role, or no one could set a route." }
    resource(name) {
        string(NotifyFields.EVENT) { required(); unique(); length(max = 64) }
        bool(NotifyFields.ENABLED) { required() }
        string(NotifyFields.DESTINATION) { required(); length(max = 64) }
        string(NotifyFields.MENTION) { length(max = 64) }
        permissions {
            val roles = editors.toTypedArray()
            read(*roles)
            create(*roles)
            update(*roles)
            delete(*roles)
        }
    }
}

/** Returns what keeps [routes] from being a notify routes resource, or an empty list. */
internal fun notifyRoutesProblems(routes: Resource): List<String> {
    val expected = mapOf(
        NotifyFields.EVENT to FieldType.STRING,
        NotifyFields.ENABLED to FieldType.BOOL,
        NotifyFields.DESTINATION to FieldType.STRING,
        NotifyFields.MENTION to FieldType.STRING,
    )
    val problems = mutableListOf<String>()
    for ((name, type) in expected) {
        val field = routes.field(name)
        when {
            field == null -> problems += "it has no '$name' field"
            field.type != type -> problems += "'$name' is a ${field.type.name.lowercase()} field, not a ${type.name.lowercase()} one"
            field.hidden -> problems += "'$name' is hidden, so no one could set it"
            field.auto != null -> problems += "'$name' is set by the server"
        }
    }
    return problems
}

/** Reads routes from a settings [resource] in [data], so a change staff make applies to the next send. */
public class ResourceNotifySettings(
    private val data: DataSource,
    private val resource: Resource,
) : NotifySettings {
    override suspend fun route(event: String): EventRoute? {
        val record = data.list(
            resource,
            Query(filter = Filter.Compare(NotifyFields.EVENT, Comparison.EQ, Value.Text(event)), limit = 1),
        ).items.firstOrNull() ?: return null
        val destination = (record[NotifyFields.DESTINATION] as? Value.Text)?.value?.takeIf { it.isNotBlank() } ?: return null
        return EventRoute(
            event = event,
            enabled = (record[NotifyFields.ENABLED] as? Value.Bool)?.value ?: false,
            destination = destination,
            mention = (record[NotifyFields.MENTION] as? Value.Text)?.value?.takeIf { it.isNotBlank() },
        )
    }
}
