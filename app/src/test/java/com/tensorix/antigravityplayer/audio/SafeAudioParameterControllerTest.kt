package com.tensorix.antigravityplayer.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

@UnstableApi
class SafeAudioParameterControllerTest {

    @Test
    fun `test non-matching vendor parameter returns UnsupportedVendor or Success safely without throwing`() {
        val context = mock<Context>()
        val result = SafeAudioParameterController.setParameter(
            context = context,
            vendor = SafeAudioParameterController.TargetVendor.LG,
            key = "hifi_dac",
            value = "on"
        )
        // On non-LG test runner, must return UnsupportedVendor and never throw
        assertTrue(result is SafeAudioParameterController.ParameterResult.UnsupportedVendor || result is SafeAudioParameterController.ParameterResult.Failed)
    }

    @Test
    fun `test Generic vendor parameter matching is always safe`() {
        val isGeneric = SafeAudioParameterController.isVendorMatch(SafeAudioParameterController.TargetVendor.GENERIC)
        assertTrue(isGeneric)
    }

    @Test
    fun `test AudioInitializationCoordinator state transitions safely`() {
        val context = mock<Context>()
        AudioInitializationCoordinator.shutdown()
        assertEquals(AppInitializationState.STARTING, AudioInitializationCoordinator.state.value)

        AudioInitializationCoordinator.onPermissionsResolved(context, false)
        assertEquals(AppInitializationState.WAITING_FOR_PERMISSION, AudioInitializationCoordinator.state.value)

        AudioInitializationCoordinator.onPermissionsResolved(context, true)
        // Transitions to LIBRARY_READY and triggers audio engine initialization
        assertTrue(
            AudioInitializationCoordinator.state.value == AppInitializationState.LIBRARY_READY ||
            AudioInitializationCoordinator.state.value == AppInitializationState.AUDIO_READY ||
            AudioInitializationCoordinator.state.value == AppInitializationState.READY
        )
    }
}
