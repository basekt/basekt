package io.github.basekt.admin

import io.github.basekt.schema.Record
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Value
import java.io.Writer

/**
 * Writes records of [resource] as RFC 4180 CSV: a header row of the id and the fields, then one row per record.
 *
 * Text cells that a spreadsheet would read as a formula (starting with `=`, `+`, `-`, `@`, a tab or a carriage
 * return) get a leading apostrophe. Numbers are written as they are.
 */
internal class CsvWriter(private val out: Writer, resource: Resource) {
    private val columns = listOf(Resource.ID) + resource.fields.map { it.name }

    fun header() {
        line(columns)
    }

    fun row(record: Record) {
        line(
            columns.map { column ->
                when (val value = record[column]) {
                    null -> ""
                    is Value.Text -> guard(value.value)
                    else -> value.toString()
                }
            },
        )
    }

    private fun line(cells: List<String>) {
        out.write(cells.joinToString(",") { quote(it) })
        out.write("\r\n")
    }

    private fun guard(text: String): String = if (text.isNotEmpty() && text[0] in FORMULA_STARTS) "'$text" else text

    private fun quote(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell

    private companion object {
        val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')
    }
}
