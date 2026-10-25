package io.github.basekt.samples.shop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Keeps the checked-in OpenAPI document in step with what the shop serves. This is the check CI runs. */
class OpenApiFileTest {
    @Test
    fun `the checked-in OpenAPI document matches the served one`() {
        val file = File(OPENAPI_FILE)
        assertTrue(file.exists(), "$OPENAPI_FILE is missing. Run ./gradlew :samples:sample-shop:generateOpenApi.")
        assertEquals(
            servedOpenApi(),
            file.readText().replace("\r\n", "\n"),
            "$OPENAPI_FILE is out of date. Run ./gradlew :samples:sample-shop:generateOpenApi and commit it.",
        )
    }

    @Test
    fun `the shop's own routes are merged in`() {
        val document = servedOpenApi()
        assertTrue("\"/low-stock\"" in document && "\"/api/products\"" in document, document.take(500))
    }
}
