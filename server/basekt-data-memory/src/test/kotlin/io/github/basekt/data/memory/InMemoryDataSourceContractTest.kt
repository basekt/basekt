package io.github.basekt.data.memory

import io.github.basekt.data.DataSource
import io.github.basekt.testing.DataSourceContract

class InMemoryDataSourceContractTest : DataSourceContract() {
    override fun createDataSource(): DataSource = InMemoryDataSource()
}
