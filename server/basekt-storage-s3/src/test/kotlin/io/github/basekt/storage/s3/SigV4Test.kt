package io.github.basekt.storage.s3

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The examples in AWS's "Authenticating Requests (AWS Signature Version 4)" documentation for S3. */
class SigV4Test {
    private val signer = SigV4("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1")
    private val now = Instant.parse("2013-05-24T00:00:00Z")
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `signs AWS's GET object example`() {
        val authorization = signer.authorization(
            "GET",
            URI("https://examplebucket.s3.amazonaws.com/test.txt"),
            mapOf("Range" to "bytes=0-9", "x-amz-content-sha256" to emptyHash, "x-amz-date" to "20130524T000000Z"),
            emptyHash,
            now,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            authorization,
        )
    }

    @Test
    fun `presigns AWS's presigned URL example`() {
        val url = signer.presign("GET", URI("https://examplebucket.s3.amazonaws.com/test.txt"), 86400, now)
        assertEquals(
            "https://examplebucket.s3.amazonaws.com/test.txt?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
                "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request" +
                "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host" +
                "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
            url.toString(),
        )
    }

    @Test
    fun `canonical queries are decoded, encoded and sorted`() {
        assertEquals("list-type=2&prefix=a%2Fb%20c&start-after=", SigV4.canonicalQuery(URI("https://h/b?start-after=&prefix=a%2Fb%20c&list-type=2")))
        assertEquals("uploads=", SigV4.canonicalQuery(URI("https://h/b/k?uploads")))
    }
}
