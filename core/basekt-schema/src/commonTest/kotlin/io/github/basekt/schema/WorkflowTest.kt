package io.github.basekt.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowTest {
    /** A made-up seed bank: samples are logged, checked, and stored or discarded. */
    private val seedBankSchema = schema {
        resource("samples") {
            string("species") { required() }
            enum("stage", listOf("logged", "checked", "stored", "discarded")) { required() }
            workflow("stage", initial = "logged") {
                transition("check", from = "logged", to = "checked") { allow("tech") }
                transition("store", from = "checked", to = "stored") {
                    allow("curator")
                    input { string("shelf") { required() } }
                    confirm("Store this sample?")
                }
                transition("discard", from = listOf("logged", "checked"), to = "discarded") { allow("curator") }
            }
            action("relabel") { allow("tech") }
            permissions { read(authenticated); write("tech") }
        }
    }

    private val samples = seedBankSchema.resource("samples")!!

    @Test
    fun `the DSL builds a workflow as data and marks its field`() {
        val workflow = samples.workflow!!
        assertEquals("stage", workflow.field)
        assertEquals("logged", workflow.initial)
        assertEquals(listOf("check", "store", "discard"), workflow.transitions.map { it.name })
        assertEquals(Auto.State, samples.field("stage")!!.auto)
        val store = samples.transition("store")!!
        assertEquals(listOf("checked"), store.from)
        assertEquals("stored", store.to)
        assertEquals(Access.Roles(setOf("curator")), store.access)
        assertEquals(listOf("shelf"), store.input.map { it.name })
        assertEquals(listOf("logged", "checked"), samples.transition("discard")!!.from)
        assertEquals(listOf("relabel", "check", "store", "discard"), samples.runnableActions.map { it.name })
        assertEquals(null, samples.action("store"))
    }

    @Test
    fun `workflows survive a JSON round trip`() {
        assertEquals(seedBankSchema, Schema.fromJson(seedBankSchema.toJson()))
    }

    @Test
    fun `clients can't set the state`() {
        val decoded = samples.decodeRecord(Json.parseToJsonElement("""{"species": "oak", "stage": "stored"}""").jsonObject, WriteMode.CREATE)
        assertIs<DecodeResult.Invalid>(decoded)
        assertEquals(FieldErrorCode.READ_ONLY, decoded.errors.single().code)
    }

    @Test
    fun `an invalid workflow is refused with its path`() {
        val error = assertFailsWith<InvalidSchemaException> {
            schema {
                resource("samples") {
                    enum("stage", listOf("a", "b"))
                    workflow("stage", initial = "z") {
                        transition("go", from = "a", to = "c") { allow("tech") }
                        transition("go", from = "a", to = "b") {}
                        transition("stay", from = emptyList(), to = "a") {}
                        transition("relabel", from = "a", to = "b") {}
                    }
                    action("relabel") {}
                }
                resource("lots") {
                    string("label")
                    workflow("label", initial = "x") {}
                }
                resource("bins") {
                    enum("state", listOf("x")) { auto(Auto.State) }
                }
            }
        }
        val paths = error.problems.map { it.path }
        for (path in listOf(
            "resources.samples.workflow",
            "resources.samples.workflow.transitions.go",
            "resources.samples.workflow.transitions.stay",
            "resources.samples.workflow.transitions.relabel",
            "resources.lots.workflow",
            "resources.bins.fields.state",
        )) {
            assertTrue(path in paths, "expected a problem at $path in $paths")
        }
    }
}
