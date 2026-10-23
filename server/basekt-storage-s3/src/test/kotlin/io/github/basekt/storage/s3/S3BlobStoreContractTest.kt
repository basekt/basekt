package io.github.basekt.storage.s3

import io.github.basekt.storage.BlobKey
import io.github.basekt.storage.BlobStore
import io.github.basekt.testing.BlobStoreContract
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.write
import java.net.URI
import java.util.UUID
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** The contract against [FakeS3], which checks every request's signature. */
class S3BlobStoreContractTest : BlobStoreContract() {
    private val fake = FakeS3("basekt-test", "AKIDEXAMPLE", "secret-example", "us-east-1")

    private fun settings(prefix: String = "") = S3Settings(fake.endpoint, "basekt-test", "us-east-1", "AKIDEXAMPLE", "secret-example", prefix = prefix)

    // Parts of the minimum 5 MiB, so the contract's 6 MiB blob is a multipart upload.
    override fun createBlobStore(): BlobStore = S3BlobStore(settings(), partSize = 5 * 1024 * 1024)

    @AfterTest
    fun stop() = fake.close()

    @Test
    fun `large blobs go up in parts`() = runTest {
        val store = S3BlobStore(settings(), partSize = 5 * 1024 * 1024)
        val data = Random(7).nextBytes(11 * 1024 * 1024)
        store.put(BlobKey.of("big/file.bin"), Buffer().apply { write(data) }, "application/octet-stream")
        assertEquals(1, fake.completedMultipart)
        assertEquals(data.size.toLong(), store.head(BlobKey.of("big/file.bin"))?.size)
    }

    @Test
    fun `a prefix keeps the store to part of the bucket`() = runTest {
        val shared = S3BlobStore(settings(prefix = "apps/basekt/"))
        shared.put(BlobKey.of("a/b.txt"), Buffer().apply { write("hi".toByteArray()) }, "text/plain")
        assertEquals(listOf("a/b.txt"), shared.list("a/").map { it.key.value })
        assertEquals(null, S3BlobStore(settings()).head(BlobKey.of("a/b.txt")))
    }

    @Test
    fun `presigned URLs read the blob without credentials`() = runTest(timeout = 1.minutes) {
        val store = S3BlobStore(settings())
        store.put(BlobKey.of("shared/doc.txt"), Buffer().apply { write("hello".toByteArray()) }, "text/plain")
        val url = store.downloadUrl(BlobKey.of("shared/doc.txt"), 10.minutes, "attachment; filename=\"doc.txt\"")
        assertContains(url, "X-Amz-Signature=")
        assertContains(url, "response-content-disposition=attachment")
        assertEquals("/basekt-test/shared/doc.txt", URI(url).rawPath)
    }

    @Test
    fun `settings are checked`() {
        for (bad in listOf<() -> Unit>(
            { S3Settings("ftp://x", "bucket", "r", "a", "s") },
            { S3Settings("http://x", "Bad_Bucket", "r", "a", "s") },
            { S3Settings("http://x", "bucket", "r", "a", "s", prefix = "no-slash") },
        )) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { bad() }
        }
        assertEquals(false, S3Settings("http://x", "bucket", "r", "a", "top-secret").toString().contains("top-secret"))
    }
}

/**
 * The contract against a real S3-compatible store, when the build is given one with `-Pbasekt.s3.*` (see
 * build.gradle.kts). Each run uses a fresh prefix in the bucket. Skipped otherwise.
 */
class RealS3BlobStoreContractTest : BlobStoreContract() {
    private val settings: S3Settings? = run {
        fun property(name: String) = System.getProperty("basekt.s3.$name")?.takeIf { it.isNotBlank() }
        val endpoint = property("endpoint") ?: return@run null
        S3Settings(
            endpoint = endpoint,
            bucket = property("bucket") ?: "basekt",
            region = property("region") ?: "us-east-1",
            accessKey = property("accessKey") ?: return@run null,
            secretKey = property("secretKey") ?: return@run null,
            prefix = "contract-${UUID.randomUUID()}/",
        )
    }

    override fun createBlobStore(): BlobStore {
        org.junit.jupiter.api.Assumptions.assumeTrue(settings != null, "No -Pbasekt.s3.* store given")
        return S3BlobStore(settings!!, partSize = 5 * 1024 * 1024)
    }
}
