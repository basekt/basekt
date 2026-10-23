package io.github.basekt.admin.typescript

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keeps the checked-in TypeScript types in step with the wire classes. This is the check CI runs. */
class TypeScriptTypesTest {
    @Test
    fun `the checked-in TypeScript types match the wire classes`() {
        val file = File(TYPESCRIPT_FILE)
        assertTrue(file.exists(), "$TYPESCRIPT_FILE is missing. Run ./gradlew :admin:basekt-admin-api:generateTypeScript.")
        assertEquals(
            typeScriptTypes(),
            file.readText().replace("\r\n", "\n"),
            "$TYPESCRIPT_FILE is out of date. Run ./gradlew :admin:basekt-admin-api:generateTypeScript and commit it.",
        )
    }

    @Test
    fun `sealed variants carry their kind and nullable properties allow null`() {
        val types = typeScriptTypes()
        assertTrue("export type Rule = RuleCustom | RuleLength | RuleMax | RuleMin | RulePattern;" in types, types)
        assertTrue("export interface RuleMin {\n  kind: \"min\";\n  value: string;\n}" in types, types)
        assertTrue("  nextCursor: string | null;" in types, types)
        assertTrue("  items: JsonObject[];" in types, types)
        assertTrue("export type ErrorCode = \"bad_request\" | " in types, types)
    }
}
