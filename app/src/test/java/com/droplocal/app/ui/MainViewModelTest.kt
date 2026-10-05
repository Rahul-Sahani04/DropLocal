package com.droplocal.app.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class MainViewModelTest {
    @Test fun draftLivesInSavedStateNotAnEditorRememberBlock() {
        val state = SavedStateHandle()
        val vm = MainViewModel(state)
        vm.updateDraft("Unsent Unicode 😀 draft")
        assertEquals("Unsent Unicode 😀 draft", state.get<String>("draft"))
        val restored = MainViewModel(SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) }))
        assertEquals(vm.draft.value, restored.draft.value)
    }
    @Test fun cancellingPendingIntentDoesNotEraseDraft() {
        val state = SavedStateHandle(mapOf("draft" to "Keep this", "pending" to "text", "pendingText" to "Keep this"))
        val vm = MainViewModel(state)
        vm.cancelPending()
        assertEquals("Keep this", vm.draft.value)
        assertNull(state.get<String>("pending"))
        assertNull(state.get<String>("pendingText"))
    }
    @Test fun displayedTransferIdentitySurvivesStateRestoration() {
        val state = SavedStateHandle()
        MainViewModel(state).showTransfer("batch-id")
        assertEquals("batch-id", MainViewModel(state).displayBatchId.value)
    }
}
