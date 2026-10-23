package io.github.basekt.storage.local

import io.github.basekt.storage.BlobStore
import io.github.basekt.testing.BlobStoreContract
import java.nio.file.Files

class LocalBlobStoreContractTest : BlobStoreContract() {
    override fun createBlobStore(): BlobStore = LocalBlobStore(Files.createTempDirectory("basekt-blobs"))
}
