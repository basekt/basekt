package io.github.basekt.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/** A made-up instrument rental: typed sessions next to a dynamic resource. */
@Serializable
private data class Session(
    val id: String? = null,
    val label: String,
    val seats: Int,
    val minutes: Long = 60,
    val fee: Value.Decimal,
    val outdoor: Boolean? = null,
    val startsAt: Instant? = null,
    val level: Level = Level.BEGINNER,
    val roomId: String? = null,
    val note: String? = null,
)

@Serializable
private enum class Level {
    @SerialName("beginner") BEGINNER,
    @SerialName("expert") EXPERT,
}

@Serializable
private data class Tagged(val id: String, val tags: List<String>)

@Serializable
private data class NoId(val name: String)

@Serializable
private data class Renamed(val id: String, @SerialName("display_name") val displayName: String)

@Serializable
private data class RescheduleInput(
    val seats: Int = 8,
    val level: Level = Level.BEGINNER,
    val tags: List<String>,
    val roomIds: List<String>? = null,
    val note: String? = null,
)

@Serializable
private data class BadActionId(val id: String)

@Serializable
private data class NullableListInput(val tags: List<String?>)

class ModelsTest {
    private val sessions = model<Session>("sessions") {
        field(Session::label) { length(max = 40) }
        field(Session::fee) { min("0") }
        belongsTo(Session::roomId, "rooms")
        permissions { read(everyone); write("host") }
    }

    @Test
    fun `a typed resource is the same resource the dynamic DSL builds`() {
        val dynamic = schema {
            resource("rooms") { string("name") }
            resource("sessions") {
                string("label") { required(); length(max = 40) }
                int("seats") { required() }
                long("minutes")
                decimal("fee") { required(); min("0") }
                bool("outdoor")
                instant("startsAt")
                enum("level", listOf("beginner", "expert"))
                belongsTo("room", "rooms")
                string("note")
                permissions { read(everyone); write("host") }
            }
        }.resource("sessions")!!
        assertEquals(dynamic.fields.associateBy { it.name }, sessions.resource.fields.associateBy { it.name })
        assertEquals(dynamic.relations, sessions.resource.relations)
        assertEquals(dynamic.permissions, sessions.resource.permissions)
    }

    @Test
    fun `typed and dynamic resources mix in one schema`() {
        lateinit var declared: Model<Session>
        val mixed = schema {
            resource("rooms") { string("name") { required() } }
            declared = resource<Session>("sessions") { belongsTo(Session::roomId, "rooms") }
        }
        assertEquals(listOf("rooms", "sessions"), mixed.resources.map { it.name })
        assertEquals(FieldType.REFERENCE, mixed.resource("sessions")!!.field("roomId")!!.type)
        assertEquals(mixed, Schema.fromJson(mixed.toJson()), "a typed resource is plain data in the JSON form too")
        assertEquals("sessions", declared.name)
    }

    @Test
    fun `every field kind round-trips through records`() {
        val value = Session(
            id = "s1",
            label = "Cello basics",
            seats = Int.MAX_VALUE,
            minutes = Long.MAX_VALUE,
            fee = Value.Decimal.of("12345678901234567890.0001"),
            outdoor = false,
            startsAt = Instant.parse("2026-11-02T18:30:00.250Z"),
            level = Level.EXPERT,
            roomId = "r1",
            note = null,
        )
        val record = sessions.toRecord(value)
        assertEquals(Value.Text("s1"), record[Resource.ID])
        assertEquals(Value.Integer(Int.MAX_VALUE.toLong()), record["seats"])
        assertEquals(Value.Decimal.of("12345678901234567890.0001"), record["fee"])
        assertEquals(Value.Timestamp(Instant.parse("2026-11-02T18:30:00.250Z")), record["startsAt"])
        assertEquals(Value.Text("expert"), record["level"])
        assertNull(record["note"])
        assertEquals(setOf("note") + sessions.resource.fields.map { it.name }, record.keys - Resource.ID)
        assertEquals(value, sessions.fromRecord(record))
    }

    @Test
    fun `missing values take the property defaults, and an empty id is left to the data source`() {
        val partial: Record = mapOf("label" to Value.Text("Drums"), "seats" to Value.Integer(4), "fee" to Value.Decimal.of("0"), "minutes" to null)
        val read = sessions.fromRecord(partial)
        assertEquals(Session(label = "Drums", seats = 4, fee = Value.Decimal.of("0")), read)
        assertEquals(null, sessions.toRecord(read)[Resource.ID])
        assertEquals(null, sessions.toRecord(read.copy(id = ""))[Resource.ID])
        assertFailsWith<IllegalArgumentException> { sessions.fromRecord(mapOf("label" to Value.Text("No seats"))) }
    }

    @Test
    fun `unsupported shapes are refused when the model is built`() {
        val list = assertFailsWith<IllegalArgumentException> { model<Tagged>("tagged") }
        assertEquals(true, list.message!!.startsWith("${Tagged.serializer().descriptor.serialName}.tags is a "), list.message)
        val noId = assertFailsWith<IllegalArgumentException> { model<NoId>("named") }
        assertEquals(true, "needs an 'id' property" in noId.message!!, noId.message)
        val renamed = assertFailsWith<IllegalArgumentException> { model<Renamed>("renamed") { field(Renamed::displayName) { required() } } }
        assertEquals(true, "@SerialName" in renamed.message!!, renamed.message)
        val notText = assertFailsWith<IllegalArgumentException> { model<Session>("x") { field(Session::id) { required() } } }
        assertEquals(true, "is the record's id" in notText.message!!, notText.message)
    }

    @Test
    fun `typed action inputs support lists, references and defaults and match dynamic action inputs`() {
        val typedInput = actionInput<RescheduleInput> {
            field(RescheduleInput::seats) { min(1); max(40) }
            reference(RescheduleInput::roomIds, "rooms")
        }
        val builtSchema = schema {
            resource("rooms") { string("name") }
            resource<Session>("sessions") {
                belongsTo(Session::roomId, "rooms")
                action("reschedule") {
                    allow("host")
                    input<RescheduleInput> {
                        field(RescheduleInput::seats) { min(1); max(40) }
                        reference(RescheduleInput::roomIds, "rooms")
                    }
                }
            }
        }
        val action = builtSchema.resource("sessions")!!.action("reschedule")!!
        assertEquals(typedInput.fields, action.input)
        assertEquals(true, action.input.first { it.name == "tags" }.list)
        assertEquals(true, action.input.first { it.name == "tags" }.required)
        assertEquals(FieldType.REFERENCE, action.input.first { it.name == "roomIds" }.type)
        assertEquals(true, action.input.first { it.name == "roomIds" }.list)

        val value = RescheduleInput(seats = 12, level = Level.EXPERT, tags = listOf("evening", "ensemble"), roomIds = listOf("r1", "r2"))
        val record = typedInput.toRecord(value)
        assertEquals(Value.Items(listOf(Value.Text("evening"), Value.Text("ensemble"))), record["tags"])
        assertEquals(value, typedInput.fromRecord(record))
        assertEquals(
            RescheduleInput(tags = listOf("solo")),
            typedInput.fromRecord(mapOf("tags" to Value.Items(listOf(Value.Text("solo"))))),
        )

        assertFailsWith<IllegalArgumentException> { actionInput<BadActionId>() }
        assertFailsWith<IllegalArgumentException> { actionInput<NullableListInput>() }
    }
}
