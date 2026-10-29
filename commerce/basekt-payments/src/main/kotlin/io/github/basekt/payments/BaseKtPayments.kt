package io.github.basekt.payments

import io.github.basekt.payment.PaymentProvider
import io.github.basekt.schema.Access
import io.github.basekt.server.BaseKt
import io.github.basekt.server.baseKt
import io.github.basekt.server.withIdentities
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Configuration of [BaseKtPayments]. Scalar settings can come from the `basekt.payments` section of `ApplicationConfig`;
 * providers, access rules and settlement hooks are set in code.
 */
public class BaseKtPaymentsConfig internal constructor(config: ApplicationConfig) {
    /** Where payment routes are mounted. `basekt.payments.path`, `/payments` by default. */
    public var path: String = config.propertyOrNull("path")?.getString() ?: "/payments"

    /** Name of the payments ledger resource in the schema. `basekt.payments.resource`, `payments` by default. */
    public var resource: String = config.propertyOrNull("resource")?.getString() ?: "payments"

    /** Name of the deduplication receipts resource in the schema. `basekt.payments.receiptsResource`, `paymentReceipts` by default. */
    public var receiptsResource: String = config.propertyOrNull("receiptsResource")?.getString() ?: "paymentReceipts"

    /**
     * How long a newly created session stays open before expiring. `basekt.payments.sessionTtl` (ISO-8601 duration such
     * as `PT30M`), `30` minutes by default; `null` leaves sessions without an expiry deadline.
     */
    public var sessionTtl: Duration? =
        config.propertyOrNull("sessionTtl")?.getString()?.let(Duration::parseIsoString) ?: 30.minutes

    /**
     * Optional interval at which `BaseKtPayments` scans [io.github.basekt.payment.OnChainConfirmable] providers for
     * incoming `Transfer` logs in the background (`basekt.payments.pollEvery`, such as `PT10S`). `null` by default.
     */
    public var pollEvery: Duration? =
        config.propertyOrNull("pollEvery")?.getString()?.let(Duration::parseIsoString)

    /** Who may create and confirm payment sessions (`Access.Everyone` by default so guest checkout works). */
    public var createAccess: Access = Access.Everyone

    /** Who may read `GET {path}/sessions/{id}` (`Access.Everyone` by default). */
    public var readAccess: Access = Access.Everyone

    /** Who may call `POST {path}/sessions/{id}/refund` (`Access.Roles(setOf("admin"))` by default). */
    public var refundAccess: Access = Access.Roles(setOf("admin"))

    /**
     * Extracts the caller's IP address for IP-allowlisted webhook/pingback providers (such as Paymentwall). Defaults to
     * Ktor's `call.request.origin`, which reflects proxy headers only when Ktor's `ForwardedHeaders` or
     * `XForwardedHeaders` plugin is installed.
     */
    public var clientIp: (ApplicationCall) -> String? = { call ->
        call.request.origin.remoteAddress.ifBlank { call.request.origin.remoteHost }
    }

    internal val providers = linkedMapOf<String, PaymentProvider>()
    internal val currencyDecimals = linkedMapOf<String, Int>()
    internal val initialScanBlocks = linkedMapOf<String, Long>()
    internal val settlementListeners = mutableListOf<suspend (PaymentSettlement) -> Unit>()

    /**
     * Registers a [PaymentProvider] (such as `PayMongoPaymentProvider` or `KaiaUsdtPaymentProvider`). For an on-chain
     * provider, [startFromBlock] sets the initial block height used when background log scanning begins (`0` by default).
     */
    public fun provider(provider: PaymentProvider, startFromBlock: Long? = null) {
        require(provider.id.isNotBlank() && '/' !in provider.id) {
            "Payment provider id '${provider.id}' must be non-blank and contain no '/'."
        }
        require(provider.id !in providers) { "Payment provider '${provider.id}' is registered twice." }
        providers[provider.id] = provider
        if (startFromBlock != null) {
            require(startFromBlock >= 0L) { "startFromBlock ($startFromBlock) must be >= 0." }
            initialScanBlocks[provider.id] = startFromBlock
        }
    }

    /** Overrides or declares the decimal scale (`0..36`) for an uppercase [currency] code. */
    public fun currencyDecimals(currency: String, decimals: Int) {
        require(decimals in 0..36) { "Decimals ($decimals) for '$currency' must be between 0 and 36." }
        currencyDecimals[currency.trim().uppercase()] = decimals
    }

    /** Registers a callback invoked after a payment status change commits in `DataSource`. */
    public fun onSettled(listener: suspend (PaymentSettlement) -> Unit) {
        settlementListeners += listener
    }
}

private val PaymentsKey = AttributeKey<PaymentsRuntime>("BaseKtPayments")

/** The running [PaymentsRuntime] installed by [BaseKtPayments]. */
public val Application.baseKtPayments: PaymentsRuntime get() = attributes[PaymentsKey]

/**
 * Serves multi-provider payment sessions, verified webhooks, on-chain confirmations, refunds and optional background
 * `Transfer` log reconciliation under [BaseKtPaymentsConfig.path]:
 *
 * | Route | What it does |
 * |---|---|
 * | `GET /providers` | lists registered providers and their capabilities |
 * | `POST /{resource}/{recordId}` | opens a payment session for a record of a `payable` resource |
 * | `GET /sessions/{id}` | returns the session's current status and checkout or wallet details |
 * | `POST /sessions/{id}/confirm` | verifies a `txHash` on-chain and settles the session atomically |
 * | `POST /sessions/{id}/refund` | refunds a settled session (guarded by [BaseKtPaymentsConfig.refundAccess]) |
 * | `GET`, `POST /webhooks/{provider}` | verifies a signed webhook or pingback, deduplicates it and settles the matching session |
 *
 * Declare `paymentLedger()` and at least one `payable { }` resource (or call [Application.baseKtPayments] directly),
 * and install [BaseKt] first.
 */
public val BaseKtPayments: ApplicationPlugin<BaseKtPaymentsConfig> = createApplicationPlugin(
    name = "BaseKtPayments",
    configurationPath = "basekt.payments",
    createConfiguration = ::BaseKtPaymentsConfig,
) {
    checkNotNull(application.pluginOrNull(BaseKt)) { "Install BaseKt before BaseKtPayments." }
    val baseKt = application.baseKt
    val base = "/" + pluginConfig.path.trim('/')
    require(base != "/" && base != baseKt.basePath) {
        "BaseKtPayments's path '$base' must differ from '/' and from BaseKt's '${baseKt.basePath}'."
    }
    require(pluginConfig.providers.isNotEmpty()) {
        "BaseKtPayments needs at least one provider: provider(PayMongoPaymentProvider(...)) or provider(KaiaUsdtPaymentProvider(...))."
    }
    pluginConfig.sessionTtl?.let {
        require(it.isPositive()) { "BaseKtPayments's sessionTtl ($it) must be positive." }
    }
    pluginConfig.pollEvery?.let {
        require(it.isPositive()) { "BaseKtPayments's pollEvery ($it) must be positive." }
    }

    val ledgerName = pluginConfig.resource
    val receiptsName = pluginConfig.receiptsResource
    require(ledgerName != receiptsName) {
        "BaseKtPayments's ledger resource ('$ledgerName') and receipts resource ('$receiptsName') must have distinct names."
    }
    val ledger = requireNotNull(baseKt.schema.resource(ledgerName)) {
        "The schema has no '$ledgerName' resource. Declare paymentLedger(\"$ledgerName\", \"$receiptsName\") in resources { }."
    }
    val receipts = requireNotNull(baseKt.schema.resource(receiptsName)) {
        "The schema has no '$receiptsName' resource. Declare paymentLedger(\"$ledgerName\", \"$receiptsName\") in resources { }."
    }

    val problems = paymentLedgerProblems(ledger, receipts) + baseKt.schema.payableProblems()
    require(problems.isEmpty()) {
        "BaseKtPayments schema configuration is not valid:\n" + problems.joinToString("\n") { "- $it" }
    }

    val log = application.log
    val runtime = PaymentsRuntime(
        path = base,
        schema = baseKt.schema,
        dataSource = baseKt.dataSource,
        ledgerResource = ledger,
        receiptsResource = receipts,
        providers = pluginConfig.providers.toMap(),
        currencyDecimals = pluginConfig.currencyDecimals.toMap(),
        sessionTtl = pluginConfig.sessionTtl,
        createAccess = pluginConfig.createAccess,
        readAccess = pluginConfig.readAccess,
        refundAccess = pluginConfig.refundAccess,
        clock = baseKt.clock,
        initialScanBlocks = pluginConfig.initialScanBlocks.toMap(),
        settlementListeners = pluginConfig.settlementListeners.toList(),
        warnLogger = { message, error -> log.warn(message, error) },
    )
    application.attributes.put(PaymentsKey, runtime)

    val identities = baseKt.identities
    val clientIp = pluginConfig.clientIp
    val registeredProviders = pluginConfig.providers.values.toList()
    application.routing {
        withIdentities(identities) {
            paymentRoutes(runtime, identities, clientIp)
        }
    }

    val pollEvery = pluginConfig.pollEvery
    var poller: Job? = null
    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt payments serves ${pluginConfig.providers.keys.joinToString()} under $base")
        if (pollEvery != null) {
            poller = app.launch {
                while (isActive) {
                    delay(pollEvery)
                    try {
                        val settled = runtime.reconcileOnChainTransfers()
                        if (settled > 0) app.log.info("BaseKt payments reconciled $settled on-chain transfer(s)")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        app.log.warn("BaseKt payments couldn't scan incoming on-chain transfers", e)
                    }
                }
            }
        }
    }
    on(MonitoringEvent(ApplicationStopping)) {
        poller?.cancel()
        for (provider in registeredProviders) {
            runCatching { (provider as? AutoCloseable)?.close() }
        }
    }
}
