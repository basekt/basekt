package io.github.basekt.notify

import io.github.basekt.schema.FieldError
import io.github.basekt.schema.FieldErrorCode
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.server.BaseKt
import io.github.basekt.server.WriteExtension
import io.github.basekt.server.baseKt
import io.github.basekt.data.DataSource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey

/** The configuration of [BaseKtNotify]. The routes resource's name can come from `basekt.notify.resource`; the sender is set in code. */
public class BaseKtNotifyConfig internal constructor(config: ApplicationConfig) {
    /** The name of the routes resource. `basekt.notify.resource`, `notifyRoutes` by default. */
    public var resource: String = config.propertyOrNull("resource")?.getString() ?: "notifyRoutes"

    /** Where messages go. Required: for example a Discord webhook sender. */
    public var sender: ChannelSender? = null

    /** Called when a send fails or a route names a destination the sender doesn't have. Logged by default. */
    public var onError: ((event: String, failure: Throwable) -> Unit)? = null
}

/**
 * Sends events to the destinations staff choose. Declare the routes resource in the schema with [notifyRoutes], install
 * [BaseKt] first, give a [BaseKtNotifyConfig.sender], and publish events with `application.notifier.publish(event, text)`.
 *
 * A route can only be saved when its destination is one the sender has and its mention (if any) suits the channel, so a
 * typo is refused when staff save it and not discovered when an event is lost. Every configuration problem is reported
 * at install, all at once.
 */
public val BaseKtNotify: ApplicationPlugin<BaseKtNotifyConfig> = createApplicationPlugin(
    name = "BaseKtNotify",
    configurationPath = "basekt.notify",
    createConfiguration = ::BaseKtNotifyConfig,
) {
    checkNotNull(application.pluginOrNull(BaseKt)) { "Install BaseKt before BaseKtNotify." }
    val baseKt = application.baseKt
    val name = pluginConfig.resource
    val routes = baseKt.schema.resource(name)
    val sender = pluginConfig.sender
    val problems = buildList {
        if (routes == null) {
            add("the schema has no '$name' resource: declare it with notifyRoutes(editors = ...) in resources { }")
        } else {
            addAll(notifyRoutesProblems(routes).map { "'$name': $it" })
        }
        if (sender == null) add("no sender: set sender = ... in install(BaseKtNotify)")
    }
    require(problems.isEmpty()) { "BaseKtNotify is not configured correctly:\n" + problems.joinToString("\n") { "- $it" } }
    requireNotNull(routes)
    requireNotNull(sender)

    val log = application.log
    val notifier = Notifier(
        settings = ResourceNotifySettings(baseKt.dataSource, routes),
        sender = sender,
        onError = pluginConfig.onError ?: { event, failure -> log.warn("Notify '$event' failed: ${failure::class.simpleName}: ${failure.message}") },
    )
    application.attributes.put(NotifierKey, notifier)
    baseKt.service.extend(RouteChecks(routes, sender))

    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt notify sends events chosen in '$name' to: ${sender.destinations.sorted().joinToString()}")
    }
}

private val NotifierKey = AttributeKey<Notifier>("BaseKtNotifier")

/** The installed [BaseKtNotify]'s notifier. Throws [IllegalStateException] if it isn't installed. */
public val Application.notifier: Notifier
    get() = attributes.getOrNull(NotifierKey) ?: error("BaseKtNotify is not installed in this application.")

/** Refuses a route whose destination the sender doesn't have, or whose mention doesn't suit the channel. */
internal class RouteChecks(private val routes: Resource, private val sender: ChannelSender) : WriteExtension {
    override suspend fun check(resource: Resource, record: Record, transaction: DataSource): List<FieldError> {
        if (resource.name != routes.name) return emptyList()
        val errors = mutableListOf<FieldError>()
        (record[NotifyFields.DESTINATION] as? Value.Text)?.value?.let { destination ->
            if (destination !in sender.destinations) {
                errors += FieldError(
                    NotifyFields.DESTINATION,
                    FieldErrorCode.REJECTED,
                    "There is no destination '$destination'. Choose one of: ${sender.destinations.sorted().joinToString()}.",
                )
            }
        }
        (record[NotifyFields.MENTION] as? Value.Text)?.value?.takeIf { it.isNotBlank() }?.let { mention ->
            sender.mentionProblem(mention)?.let { problem ->
                errors += FieldError(NotifyFields.MENTION, FieldErrorCode.REJECTED, problem)
            }
        }
        return errors
    }
}
