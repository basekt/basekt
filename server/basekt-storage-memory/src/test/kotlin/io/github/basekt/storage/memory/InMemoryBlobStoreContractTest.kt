package io.github.basekt.storage.memory

import io.github.basekt.storage.BlobStore
import io.github.basekt.testing.BlobStoreContract

class InMemoryBlobStoreContractTest : BlobStoreContract() {
    override fun createBlobStore(): BlobStore = InMemoryBlobStore()
}
