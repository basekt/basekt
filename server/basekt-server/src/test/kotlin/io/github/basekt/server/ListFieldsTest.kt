package io.github.basekt.server

import io.github.basekt.data.DataSource
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.InvalidSchemaException
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A made-up recipe box: a recipe keeps its steps (with ids, so cooks' notes can point at one), tags and tips. */
private val recipeSchema: Schema = schema {
    resource("cooks") {
        string("name") { required() }
        permissions { read(authenticated); write(authenticated) }
    }
    resource("recipes") {
        string("title") { required() }
        objects("steps") {
            itemIds()
            required()
            string("text") { required(); markdown() }
            int("minutes") { min(0) }
        }
        string("tags") { list(); length(max = 12) }
        string("tips") { list(); markdown() }
        reference("cookIds", "cooks") { list() }
        permissions { read(authenticated); write(authenticated) }
    }
}

/** A made-up bird count: a sighting lists the species seen, as plain values without ids. */
private val birdSchema: Schema = schema {
    resource("sightings") {
        string("place") { required() }
        objects("counts") {
            enum("species", listOf("heron", "kite", "tern")) { required() }
            int("number") { required(); min(1) }
        }
        permissions { read(everyone); write(everyone) }
    }
}

/** A source that stores records but doesn't say it can store lists. */
private class PlainSource(source: DataSource = InMemoryDataSource()) : DataSource by source

class ListFieldsTest {
    private fun app(schema: Schema = recipeSchema, test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                this.schema = schema
                identities = headerIdentities
            }
        }
        test()
    }

    private suspend fun ApplicationTestBuilder.recipe(body: String) = client.post("/api/recipes") { caller("u1"); json(body) }

    private suspend fun io.ktor.client.statement.HttpResponse.obj(): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.stepIds(): List<String> = this["steps"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `a record keeps its lists, and each new item gets an id it keeps through edits and reorders`() = app {
        client.post("/api/cooks") { caller("u1"); json("""{"id": "c1", "name": "Ana"}""") }
        val created = recipe(
            """{"id": "r1", "title": "Soup", "steps": [{"text": "Chop", "minutes": 5}, {"id": "s-boil", "text": "Boil"}],
               "tags": ["quick", "warm"], "tips": ["Use **fresh** herbs."], "cookIds": ["c1"]}""",
        )
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val ids = created.obj().stepIds()
        assertEquals(24, ids[0].length, "a new item gets a generated id")
        assertEquals("s-boil", ids[1], "an item that comes with an id keeps it")

        val reordered = client.patch("/api/recipes/r1") {
            caller("u1")
            json("""{"steps": [{"id": "s-boil", "text": "Boil well"}, {"id": "${ids[0]}", "text": "Chop", "minutes": 5}, {"text": "Serve"}]}""")
        }
        assertEquals(HttpStatusCode.OK, reordered.status, reordered.bodyAsText())
        val after = reordered.obj().stepIds()
        assertEquals(listOf("s-boil", ids[0]), after.take(2))
        assertNotEquals(ids[0], after[2])
        assertEquals(listOf("quick", "warm"), reordered.obj()["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `an error names the item and the field it is about`() = app {
        val missingText = recipe("""{"title": "Toast", "steps": [{"text": "Slice"}, {"minutes": 2}]}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, missingText.status)
        val error = missingText.obj()["fields"]!!.jsonArray.single().jsonObject
        assertEquals("steps.1.text", error["field"]!!.jsonPrimitive.content)
        assertEquals("required", error["code"]!!.jsonPrimitive.content)

        val unknown = recipe("""{"title": "Toast", "steps": [{"text": "Slice", "colour": "red"}]}""")
        assertEquals("steps.0.colour", unknown.obj()["fields"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content)

        val longTag = recipe("""{"title": "Toast", "steps": [{"text": "Slice"}], "tags": ["ok", "far too long a tag"]}""")
        assertEquals("tags.1", longTag.obj()["fields"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content)

        val twice = recipe("""{"title": "Toast", "steps": [{"id": "a", "text": "Slice"}, {"id": "a", "text": "Toast"}]}""")
        assertEquals("steps.1.id", twice.obj()["fields"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content)

        val none = recipe("""{"title": "Toast", "steps": []}""")
        assertEquals("required", none.obj()["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)

        val missingCook = recipe("""{"title": "Toast", "steps": [{"text": "Slice"}], "cookIds": ["nobody"]}""")
        assertEquals("missing_reference", missingCook.obj()["fields"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `has finds records whose list holds a value, and a list can't be sorted on or compared`() = app {
        recipe("""{"id": "r1", "title": "Soup", "steps": [{"text": "Boil"}], "tags": ["warm", "quick"]}""")
        recipe("""{"id": "r2", "title": "Salad", "steps": [{"text": "Toss"}], "tags": ["cold", "quick"]}""")
        suspend fun ids(query: String) = client.get("/api/recipes?$query") { caller("u1") }.obj()["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("r1"), ids("tags[has]=warm"))
        assertEquals(listOf("r1", "r2"), ids("tags[has]=warm,cold&sort=id"))
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/recipes?sort=tags") { caller("u1") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/recipes?tags=warm") { caller("u1") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/recipes?steps[has]=x") { caller("u1") }.status)
    }

    @Test
    fun `items without ids are plain values`() = app(birdSchema) {
        val created = client.post("/api/sightings") { json("""{"place": "Marsh", "counts": [{"species": "heron", "number": 2}, {"species": "tern", "number": 7}]}""") }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val counts = created.obj()["counts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(setOf("species", "number"), setOf("species", "number")), counts.map { it.keys })
        val withId = client.post("/api/sightings") { json("""{"place": "Marsh", "counts": [{"id": "x", "species": "kite", "number": 1}]}""") }
        assertEquals("counts.0.id", withId.obj()["fields"]!!.jsonArray.single().jsonObject["field"]!!.jsonPrimitive.content)
        val bad = client.post("/api/sightings") { json("""{"place": "Marsh", "counts": [{"species": "owl", "number": 0}]}""") }
        assertEquals(setOf("counts.0.species", "counts.0.number"), bad.obj()["fields"]!!.jsonArray.map { it.jsonObject["field"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `a data source that can't store lists is refused at install`() {
        val error = assertFailsWith<Throwable> {
            testApplication {
                application { install(BaseKt) { dataSource = PlainSource(); schema = birdSchema } }
                startApplication()
            }
        }
        assertTrue(generateSequence(error) { it.cause }.any { "can't store list or object fields, which sightings.counts are" in it.message.orEmpty() })
    }

    @Test
    fun `the schema refuses lists where they can't work`() {
        fun problems(block: io.github.basekt.schema.ResourceBuilder.() -> Unit) = assertFailsWith<InvalidSchemaException> {
            schema {
                resource("others") { string("name") }
                resource("things") { block() }
            }
        }.problems.map { it.message }
        assertTrue(problems { string("tags") { list(); unique() } }.single().contains("can't be unique"))
        assertTrue(problems { instant("seen") { list(); createdAt() } }.any { it.contains("set automatically") })
        assertTrue(problems { objects("parts") { } }.single().contains("at least one field"))
        assertTrue(problems { objects("parts") { file("photo", "media") } }.single().contains("can't hold files"))
        assertTrue(problems { objects("parts") { reference("otherId", "others") } }.single().contains("can't hold references"))
        val throughList = assertFailsWith<InvalidSchemaException> {
            schema {
                resource("others") { hasMany("things", "things", foreignField = "otherIds") }
                resource("things") { reference("otherIds", "others") { list() } }
            }
        }
        assertTrue(throughList.problems.single().message.contains("list of references, which a relation can't use yet"))
    }
}
