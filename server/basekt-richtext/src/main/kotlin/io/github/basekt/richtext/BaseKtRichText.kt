package io.github.basekt.richtext

import io.github.basekt.data.DataSource
import io.github.basekt.schema.Field
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
    val fields = baseKt.schema.resources.sumOf { resource -> resource.fields.sumOf { it.markdownCount() } }
    baseKt.service.extend(MarkdownFields(pluginConfig.mathValidator))

    on(MonitoringEvent(ApplicationStarted)) { app ->
        app.log.info("BaseKt rich text checks $fields Markdown fields")
    }
}

/** How many Markdown fields this field is, counting those inside an object list's items. */
private fun Field.markdownCount(): Int = (if (markdown != null) 1 else 0) + fields.orEmpty().sumOf { it.markdownCount() }

private class MarkdownFields(private val mathValidator: MathValidator) : WriteExtension {
    override suspend fun check(resource: Resource, record: Record, transaction: DataSource): List<FieldError> =
        resource.fields.flatMap { field -> record[field.name]?.let { check(field, it, field.name) }.orEmpty() }

    /** Checks [value] of [field] at [path]: each item of a list at `path.i`, and each field of an item at `path.i.name`. */
    private fun check(field: Field, value: Value, path: String): List<FieldError> = when (value) {
        is Value.Items -> value.values.flatMapIndexed { i, item -> check(field.copy(list = false), item, "$path.$i") }
        is Value.Object -> field.fields.orEmpty().flatMap { sub -> value.values[sub.name]?.let { check(sub, it, "$path.${sub.name}") }.orEmpty() }
        is Value.Text -> {
            val options = field.markdown
            val problems = if (options == null) emptyList() else Markdown.check(value.value, options, mathValidator)
            if (problems.isEmpty()) emptyList() else listOf(FieldError(path, FieldErrorCode.CUSTOM, problems.joinToString(" ")))
        }
        else -> emptyList()
    }
}
