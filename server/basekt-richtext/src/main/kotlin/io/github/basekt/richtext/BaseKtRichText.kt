package io.github.basekt.richtext

import io.github.basekt.data.DataSource
import io.github.basekt.schema.FieldError
import io.github.basekt.schema.FieldErrorCode
import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import io.github.basekt.server.BaseKt
import io.github.basekt.server.WriteExtension
import io.github.basekt.server.baseKt
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.log
import io.ktor.server.application.pluginOrNull

/** The configuration of [BaseKtRichText]. */
public class BaseKtRichTextConfig internal constructor() {
    /** Checks each formula of a field with `markdown { math() }`. [MathValidator.Basic] unless set. */
    public var mathValidator: MathValidator = MathValidator.Basic
}

/**
 * Checks every Markdown field of the schema (`string("body") { markdown { } }`) on every create and update, from every
 * route, hook-free and inside the write's transaction. A write whose text breaks the field's options fails with
 * `validation_failed` and a message for the field: raw HTML, an image where images are off, a link to an unsafe scheme,
 * a formula the [BaseKtRichTextConfig.mathValidator] refuses, or a `$$` block that is never closed. The schema itself
 * already caps the length.
 *
 * Render what is stored with [Markdown.toHtml] and [Markdown.toPlainText]. Install [BaseKt] first. Writes that trusted
 * code makes through raw data access, such as a hook's `data`, aren't checked, and neither is the input of an action.
 */
public val BaseKtRichText: ApplicationPlugin<BaseKtRichTextConfig> = createApplicationPlugin(
    name = "BaseKtRichText",
    createConfiguration = { BaseKtRichTextConfig() },
) {
    checkNotNull(application.pluginOrNull(BaseKt)) { "Install BaseKt before BaseKtRichText." }
    val baseKt = application.baseKt
    val fields = baseKt.schema.resources.sumOf { resource -> resource.fields.count { it.markdown != null } }
    baseKt.service.extend(MarkdownFields(pluginConfig.mathValidator))

    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt rich text checks $fields Markdown fields")
    }
}

private class MarkdownFields(private val mathValidator: MathValidator) : WriteExtension {
    override suspend fun check(resource: Resource, record: Record, transaction: DataSource): List<FieldError> =
        resource.fields.mapNotNull { field ->
            val options = field.markdown ?: return@mapNotNull null
            val text = (record[field.name] as? Value.Text)?.value ?: return@mapNotNull null
            Markdown.check(text, options, mathValidator).takeIf { it.isNotEmpty() }
                ?.let { FieldError(field.name, FieldErrorCode.CUSTOM, it.joinToString(" ")) }
        }
}
