package io.github.basekt.discord.link.memory

import io.github.basekt.discord.link.LinkedAccounts
import io.github.basekt.discord.link.PendingLinkStore
import io.github.basekt.testing.LinkedAccountsContract
import io.github.basekt.testing.PendingLinkStoreContract

class InMemoryPendingLinkStoreTest : PendingLinkStoreContract() {
    override fun createPendingLinkStore(): PendingLinkStore = InMemoryPendingLinkStore()
}

class InMemoryLinkedAccountsTest : LinkedAccountsContract() {
    override fun createLinkedAccounts(): LinkedAccounts = InMemoryLinkedAccounts()
}
