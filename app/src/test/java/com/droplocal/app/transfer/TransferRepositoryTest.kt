package com.droplocal.app.transfer

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TransferRepositoryTest {
    @Test fun terminalCallbacksCannotRegressOrCreateAdditionalHistory() = runTest {
        for (terminal in listOf(TransferStatus.DONE, TransferStatus.FAILED, TransferStatus.CANCELLED)) {
            val records = mutableListOf<TransferSession>()
            val repository = TransferRepository({ records += it }, backgroundScope)
            repository.enqueue(TransferSession(id = "id", name = "file", size = 10))
            when (terminal) {
                TransferStatus.DONE -> repository.complete("id", "content://downloads/1")
                TransferStatus.FAILED -> repository.fail("id", "failure")
                else -> repository.cancel("id")
            }
            repository.progress("id", 5, 20)
            repository.running("id")
            repository.waiting("id")
            repository.saving("id")
            repository.complete("id")
            repository.fail("id", "late failure")
            repository.cancel("id")
            runCurrent()
            assertEquals(terminal, repository.queue.items.value.single().status)
            assertEquals(1, records.size)
            assertEquals(terminal, records.single().status)
        }
    }

    @Test fun snapshotSurvivesImmediateRetryAndRecordsInOrder() = runTest {
        val records = mutableListOf<TransferSession>()
        val repository = TransferRepository({ records += it }, backgroundScope)
        repository.enqueue(TransferSession(id = "id", name = "file", size = -1))
        repository.progress("id", 3, 5)
        repository.fail("id", "first attempt")
        repository.retry("id")
        repository.progress("id", 7, 7)
        repository.complete("id", "content://downloads/7")
        runCurrent()
        assertEquals(listOf(TransferStatus.FAILED, TransferStatus.DONE), records.map { it.status })
        assertEquals(3L, records.first().bytes)
        assertEquals("first attempt", records.first().error)
        assertEquals(7L, records.last().bytes)
        assertEquals("content://downloads/7", records.last().outputUri)
        assertEquals(1, repository.queue.items.value.size)
    }

    @Test fun savingIsNotRevertedByLateProgressAndActualCountIsPreserved() = runTest {
        val repository = TransferRepository({}, backgroundScope)
        repository.enqueue(TransferSession(id = "id", name = "file", size = 100))
        repository.waiting("id")
        assertEquals(TransferStatus.WAITING, repository.queue.active()?.status)
        repository.running("id")
        repository.progress("id", 10)
        repository.progress("id", 5)
        assertEquals(10L, repository.queue.active()?.bytes)
        repository.saving("id")
        repository.progress("id", 42, 42)
        assertEquals(TransferStatus.SAVING, repository.queue.active()?.status)
        repository.complete("id")
        val final = repository.queue.items.value.single()
        assertEquals(42L, final.bytes)
        assertEquals(42L, final.size)
    }
}
