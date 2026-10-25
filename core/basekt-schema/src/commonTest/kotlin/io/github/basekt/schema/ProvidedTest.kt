package io.github.basekt.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvidedTest {
    /** A made-up weather station: its sensors are stored, and their live readings come from the station's own code. */
    private val stationSchema = schema {
        resource("sensors") {
            string("label") { required() }
            permissions { read(everyone); write("technician") }
        }
        resource("readings") {
            provided()
            belongsTo("sensor", "sensors")
            decimal("value")
            instant("takenAt")
            action("recalibrate") { allow("technician") }
            permissions { read(authenticated) }
        }
    }

    @Test
    fun `a provided resource keeps its flag in the JSON form, and a stored one leaves it out`() {
        val json = stationSchema.toJson()
        assertTrue(stationSchema.resource("readings")!!.provided)
        assertFalse(stationSchema.resource("sensors")!!.provided)
        assertEquals(1, Regex("\"provided\"").findAll(json).count())
        assertEquals(stationSchema, Schema.fromJson(json))
    }

    @Test
    fun `everything a write would need is refused on a provided resource, all at once`() {
        val resource = Resource(
            name = "queue",
            fields = listOf(
                Field("position", FieldType.INT, unique = true),
                Field("seenAt", FieldType.INSTANT, auto = Auto.UpdatedAt),
                Field("state", FieldType.ENUM, values = listOf("waiting", "done"), auto = Auto.State),
                Field("attachment", FieldType.FILE, bucket = "uploads"),
            ),
            permissions = Permissions(read = Access.Everyone, create = Access.Everyone, delete = Access.Authenticated),
            workflow = Workflow("state", "waiting"),
            reports = listOf(Report("byState", Chart.PIE, groupBy = "state")),
            id = IdSpec(IdSource.NATURAL),
            provided = true,
        )
        val messages = Schema(listOf(resource)).problems().map { "${it.path}: ${it.message}" }
        val expected = listOf(
            "resources.queue.permissions: A provided resource is read-only, so it can't grant create.",
            "resources.queue.permissions: A provided resource is read-only, so it can't grant delete.",
            "resources.queue.workflow: A provided resource is read-only, so it can't have a workflow.",
            "resources.queue.reports: A provided resource can't be aggregated, so it can't have reports.",
            "resources.queue.id: A provided resource's ids come from its provider, so they take no source, rules or key.",
            "resources.queue.fields.position: A provided resource is never written, so a field can't be unique.",
            "resources.queue.fields.seenAt: A provided resource is never written, so the server can't set its fields.",
            "resources.queue.fields.attachment: A provided resource is never written, so it can't have a file field.",
        )
        for (message in expected) assertTrue(message in messages, "Missing \"$message\" in $messages")
    }

    @Test
    fun `no reference field points to a provided resource, so nothing has many of it either`() {
        val problems = Schema(
            listOf(
                Resource("tickets", listOf(Field("ticketId", FieldType.STRING)), provided = true),
                Resource(
                    "notes",
                    listOf(Field("ticketId", FieldType.REFERENCE, target = "tickets")),
                    relations = listOf(Relation.BelongsTo("ticket", "tickets", "ticketId")),
                ),
            ),
        ).problems()
        assertEquals(
            listOf(SchemaProblem("resources.notes.fields.ticketId", "'tickets' is provided by the application's code, so no reference field can point to it.")),
            problems,
        )
    }
}
