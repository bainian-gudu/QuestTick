package com.questtick.repository.base

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatefulRepositoryTest {
    @Test
    fun `successful empty load is distinguishable from initial loading`() = runTest {
        val repository = TestRepository(emptyList())

        assertTrue(repository.loadState.value is RepositoryLoadState.InitialLoading)
        repository.reload()

        assertTrue(repository.loaded.value)
        assertTrue(repository.loadState.value is RepositoryLoadState.Empty)
        assertTrue(repository.state.value.isEmpty())
    }

    @Test
    fun `successful content load publishes content state`() = runTest {
        val repository = TestRepository(listOf("saved"))

        repository.reload()

        assertEquals(listOf("saved"), repository.state.value)
        assertTrue(repository.loadState.value is RepositoryLoadState.Content)
    }

    @Test
    fun `failed first load keeps initial value and exposes recoverable error`() = runTest {
        val repository = TestRepository(emptyList(), failure = IllegalStateException("disk unavailable"))

        val failure = runCatching { repository.reload() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(repository.loaded.value)
        assertEquals(emptyList<String>(), repository.state.value)
        val state = repository.loadState.value as RepositoryLoadState.Error
        assertFalse(state.hasContent)
        assertEquals("IllegalStateException", state.errorType)
    }

    @Test
    fun `failed refresh keeps last successful snapshot`() = runTest {
        val repository = TestRepository(listOf("saved"))
        repository.reload()
        repository.failure = IllegalArgumentException("corrupt")

        runCatching { repository.reload() }

        assertTrue(repository.loaded.value)
        assertEquals(listOf("saved"), repository.state.value)
        val state = repository.loadState.value as RepositoryLoadState.Error
        assertTrue(state.hasContent)
        assertEquals("IllegalArgumentException", state.errorType)
    }

    @Test
    fun `manual retry can recover from read failure`() = runTest {
        val repository = TestRepository(emptyList(), failure = IllegalStateException("temporary"))
        runCatching { repository.reload() }

        repository.failure = null
        repository.stored = listOf("recovered")
        repository.reload()

        assertEquals(listOf("recovered"), repository.state.value)
        assertTrue(repository.loadState.value is RepositoryLoadState.Content)
    }

    private class TestRepository(
        var stored: List<String>,
        var failure: RuntimeException? = null,
    ) : StatefulRepository<List<String>>(
            initial = emptyList(),
            isEmpty = List<String>::isEmpty,
            initialLoadEnabled = false,
        ) {
        override suspend fun loadFromStore(): List<String> {
            failure?.let { throw it }
            return stored
        }

        override suspend fun saveToStore(data: List<String>) {
            stored = data
        }
    }
}
