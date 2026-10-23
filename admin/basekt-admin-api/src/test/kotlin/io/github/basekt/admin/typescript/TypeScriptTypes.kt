package io.github.basekt.admin.typescript

import io.github.basekt.admin.ADMIN_SCHEMA_VERSION
import io.github.basekt.admin.AdminAggregatePage
import io.github.basekt.admin.AdminPage
import io.github.basekt.admin.AdminSchema
import io.github.basekt.admin.BulkDeleteRequest
import io.github.basekt.admin.BulkDeleteResult
import io.github.basekt.server.ApiError
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import java.io.File

/** Where the generated types are checked in, relative to the module. */
const val TYPESCRIPT_FILE: String = "typescript/basekt-admin-api.ts"

/** The wire types an admin panel uses, and everything they reference. */
private val roots = listOf(
    AdminSchema.serializer().descriptor,
    AdminPage.serializer().descriptor,
    AdminAggregatePage.serializer().descriptor,
    BulkDeleteRequest.serializer().descriptor,
    BulkDeleteResult.serializer().descriptor,
    ApiError.serializer().descriptor,
)

/** Writes the TypeScript types to the file named by the first argument (`generateTypeScript` runs this). */
fun main(args: Array<String>) {
    val file = File(args.single())
    file.parentFile.mkdirs()
    file.writeText(typeScriptTypes())
    println("Wrote ${file.path}")
}

/**
 * Returns TypeScript declarations for the admin API's wire format, generated from the `@Serializable` classes'
 * descriptors and matching how the admin API encodes them: every property present, `null` where nullable, and a
 * `kind` property on sealed variants.
 */
fun typeScriptTypes(): String {
    val writer = Declarations()
    roots.forEach(writer::typeOf)
    val declarations = writer.declarations
    return buildString {
        append("// Generated from the admin API's @Serializable classes by ./gradlew :admin:basekt-admin-api:generateTypeScript.\n")
        append("// Do not edit: CI fails if this file doesn't match the classes.\n")
        append("//\n")
        append("// The contract is additive only within a version: properties and enum values may be added, and clients\n")
        append("// must ignore ones they don't know. Removing, renaming or retyping anything needs a new version.\n\n")
        append("/** The AdminSchema contract version these types describe. */\n")
        append("export const ADMIN_SCHEMA_VERSION = $ADMIN_SCHEMA_VERSION;\n\n")
        append("/** Any JSON value. */\n")
        append("export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };\n\n")
        append("/** A JSON object, such as a record keyed by field name. */\n")
        append("export type JsonObject = { [key: string]: JsonValue };\n")
        for (declaration in declarations.values) append("\n").append(declaration).append("\n")
    }
}

/** Collects one declaration per named type, in the order they're first referenced. */
@OptIn(ExperimentalSerializationApi::class)
private class Declarations {
    val declarations = linkedMapOf<String, String>()
    private val names = mutableMapOf<String, String>()

    fun typeOf(descriptor: SerialDescriptor): String {
        val base = when (descriptor.serialName.removeSuffix("?")) {
            "kotlinx.serialization.json.JsonObject" -> "JsonObject"
            "kotlinx.serialization.json.JsonElement" -> "JsonValue"
            else -> when (val kind = descriptor.kind) {
                PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
                PrimitiveKind.BOOLEAN -> "boolean"
                is PrimitiveKind -> "number"
                StructureKind.LIST -> typeOf(descriptor.getElementDescriptor(0)).let { if (" " in it) "($it)[]" else "$it[]" }
                StructureKind.MAP -> "Record<string, ${typeOf(descriptor.getElementDescriptor(1))}>"
                else -> declare(descriptor, kind)
            }
        }
        return if (descriptor.isNullable) "$base | null" else base
    }

    private fun declare(descriptor: SerialDescriptor, kind: SerialKind, variantOf: String? = null): String {
        val key = descriptor.serialName.removeSuffix("?")
        names[key]?.let { return it }
        val name = variantOf?.let { it + key.split('-', '_').joinToString("") { part -> part.replaceFirstChar(Char::uppercase) } }
            ?: key.substringAfterLast('.')
        names[key] = name
        declarations[name] = ""
        declarations[name] = when (kind) {
            SerialKind.ENUM -> "export type $name = ${(0 until descriptor.elementsCount).joinToString(" | ") { "\"${descriptor.getElementName(it)}\"" }};"
            PolymorphicKind.SEALED -> {
                val variants = descriptor.getElementDescriptor(1).elementDescriptors.map { declare(it, it.kind, variantOf = name) }
                "export type $name = ${variants.joinToString(" | ")};"
            }
            StructureKind.CLASS, StructureKind.OBJECT -> buildString {
                append("export interface $name {\n")
                if (variantOf != null) append("  kind: \"$key\";\n")
                for (i in 0 until descriptor.elementsCount) {
                    append("  ${descriptor.getElementName(i)}: ${typeOf(descriptor.getElementDescriptor(i))};\n")
                }
                append("}")
            }
            else -> error("No TypeScript mapping for ${descriptor.serialName} ($kind).")
        }
        return name
    }
}
