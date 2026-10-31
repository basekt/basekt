package io.github.basekt.server

import io.github.basekt.data.TypedConditionalUpdate
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.ActionTarget
import io.github.basekt.schema.input
import io.github.basekt.schema.model
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/** First made-up schema: a music conservatory's instrument locker room and loans. */
@Serializable
private data class Instrument(
    val id: String? = null,
    val name: String,
    val available: Int,
    val condition: Condition = Condition.GOOD,
)

@Serializable
private enum class Condition {
    @SerialName("good") GOOD,
    @SerialName("repair") REPAIR,
}

@Serializable
private data class Loan(
    val id: String? = null,
    val instrumentId: String,
    val student: String,
    val days: Int = 7,
    val createdAt: Instant? = null,
)

@Serializable
private data class CheckoutInput(
    val students: List<String>,
    val days: Int = 7,
)

@Serializable
private data class CheckoutReceipt(
    val created: Int,
    val remaining: Int,
)

/** Second made-up schema: a community garden's seed packets and seasonal purges. */
@Serializable
private data class SeedPacket(
    val id: String? = null,
    val variety: String,
    val harvestYear: Int,
    val retired: Boolean = false,
)

@Serializable
private data class PurgeInput(
    val beforeYear: Int,
)

@Serializable
private data class PurgeReport(
    val removed: Int,
)

private val Instruments = model<Instrument>("instruments") {
    naturalId()
    field(Instrument::available) { min(0) }
    permissions { read(authenticated); write("curator") }
    action("checkout") {
        allow("curator")
        input<CheckoutInput> {
            field(CheckoutInput::days) { min(1); max(30) }
        }
    }
    action("sendToRepair") {
        allow("curator")
    }
}

private val Loans = model<Loan>("loans") {
    belongsTo(Loan::instrumentId, "instruments")
    field(Loan::createdAt) { createdAt() }
    permissions { read(authenticated); write("curator") }
}

class TypedActionsTest {
    @Test
    fun `typed record actions decode list inputs, run conditional updates, stamp auto fields and encode answers`() = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                identities = headerIdentities
                resources {
                    resource(Instruments)
                    resource(Loans)
                }
                actions {
                    on(Instruments, "checkout") { instrument: Instrument, input: CheckoutInput ->
                        val cleaned = input.students.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                        if (cleaned.isEmpty()) fail(CheckoutInput::students, "Name at least one student.")
                        val updated = when (
                            val step = repository(Instruments).updateIf(instrument.id!!) {
                                where { (Instrument::available gte cleaned.size) and (Instrument::condition eq Condition.GOOD) }
                                increment(Instrument::available, -cleaned.size)
                            }
                        ) {
                            is TypedConditionalUpdate.Updated -> step.value
                            TypedConditionalUpdate.ConditionFailed -> fail("Not enough instruments in good condition.")
                            TypedConditionalUpdate.NotFound -> fail("Instrument missing.")
                        }
                        for (student in cleaned) {
                            create(Loans, Loan(instrumentId = instrument.id, student = student, days = input.days))
                        }
                        CheckoutReceipt(created = cleaned.size, remaining = updated.available)
                    }
                    onRecord<Instrument, Unit>("sendToRepair") { instrument ->
                        update(Instruments, instrument.id!!, instrument.copy(condition = Condition.REPAIR))
                    }
                }
            }
        }

        client.postJson("/api/instruments", """{"id": "cello-1", "name": "Cello", "available": 3, "condition": "good"}""", "curator")

        val receipt = client.postJson(
            "/api/instruments/cello-1/actions/checkout",
            """{"students": ["Clara", " Nadia "]}""",
            "curator",
        )
        assertEquals(HttpStatusCode.OK, receipt.status)
        assertEquals(2, receipt.json()["created"]!!.jsonPrimitive.int)
        assertEquals(1, receipt.json()["remaining"]!!.jsonPrimitive.int)

        val loans = client.get("/api/loans?sort=student") { asUser("curator") }.json()["items"]!!.jsonArray
        assertEquals(listOf("Clara", "Nadia"), loans.map { it.jsonObject["student"]!!.jsonPrimitive.content })
        assertEquals(7, loans.first().jsonObject["days"]!!.jsonPrimitive.int)
        assertEquals(true, loans.first().jsonObject["createdAt"]!!.jsonPrimitive.content.isNotEmpty())

        val blankStudents = client.postJson(
            "/api/instruments/cello-1/actions/checkout",
            """{"students": ["   "]}""",
            "curator",
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, blankStudents.status)
        assertEquals("students", blankStudents.json()["fields"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content)

        val repaired = client.postJson("/api/instruments/cello-1/actions/sendToRepair", "{}", "curator")
        assertEquals(HttpStatusCode.OK, repaired.status)
        assertEquals("repair", repaired.json()["condition"]!!.jsonPrimitive.content)

        val refused = client.postJson(
            "/api/instruments/cello-1/actions/checkout",
            """{"students": ["Bela"]}""",
            "curator",
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("rejected", refused.json()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `typed resource actions delete matching records and typed actions reject undeclared models at install`() {
        val seeds = model<SeedPacket>("seedPackets") {
            permissions { read(authenticated); write("gardener") }
            action("purgeOld", ActionTarget.RESOURCE) {
                allow("gardener")
                input<PurgeInput> { field(PurgeInput::beforeYear) { min(1900) } }
            }
        }

        testApplication {
            application {
                install(BaseKt) {
                    dataSource = InMemoryDataSource()
                    identities = headerIdentities
                    resources { resource(seeds) }
                    actions {
                        onResource(seeds, "purgeOld") { input: PurgeInput ->
                            val count = deleteWhere(seeds) { SeedPacket::harvestYear lt input.beforeYear }
                            PurgeReport(removed = count)
                        }
                    }
                }
            }

            client.postJson("/api/seedPackets", """{"variety": "Heirloom Tomato", "harvestYear": 2021}""", "gardener")
            client.postJson("/api/seedPackets", """{"variety": "Sweet Basil", "harvestYear": 2025}""", "gardener")

            val purged = client.postJson("/api/seedPackets/actions/purgeOld", """{"beforeYear": 2024}""", "gardener")
            assertEquals(HttpStatusCode.OK, purged.status)
            assertEquals(1, purged.json()["removed"]!!.jsonPrimitive.int)

            val remaining = client.get("/api/seedPackets") { asUser("gardener") }.json()["items"]!!.jsonArray
            assertEquals(listOf("Sweet Basil"), remaining.map { it.jsonObject["variety"]!!.jsonPrimitive.content })
        }

        assertFailsWith<IllegalStateException> {
            testApplication {
                application {
                    install(BaseKt) {
                        dataSource = InMemoryDataSource()
                        schema = trackerSchema
                        actions { onRecord<Instrument, Unit>("sendToRepair") { } }
                    }
                }
                startApplication()
            }
        }
    }
}
