package com.rtbishop.look4sat.feature.sunlight

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.rtbishop.look4sat.core.domain.model.SatItem
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.sunlight.ISunlightPredictor
import com.rtbishop.look4sat.core.domain.sunlight.Illumination
import com.rtbishop.look4sat.core.domain.sunlight.IlluminationInterval
import com.rtbishop.look4sat.core.domain.sunlight.SunlightPrediction
import com.rtbishop.look4sat.core.domain.sunlight.SunlightRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SunlightViewModelTest {
    private val store = ViewModelStore()
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private class Predictor : ISunlightPredictor {
        val requests = mutableListOf<SunlightRequest>()
        val completions = mutableListOf<CompletableDeferred<SunlightPrediction>>()
        val callbacks = mutableListOf<(Double) -> Unit>()
        override suspend fun satellites() = listOf(SatItem(1, "Alpha"), SatItem(2, "Zulu"))
        override suspend fun predict(request: SunlightRequest, onProgress: (Double) -> Unit): SunlightPrediction {
            requests += request
            callbacks += onProgress
            return CompletableDeferred<SunlightPrediction>().also { completions += it }.await()
        }
    }

    private fun model(
        predictor: Predictor,
        saved: SavedStateHandle = SavedStateHandle(),
        utc: MutableStateFlow<Boolean> = MutableStateFlow(false)
    ): SunlightViewModel = SunlightViewModel(predictor, MutableStateFlow(listOf(2)), utc, saved).also {
        store.put("model-${store.keys().size}", it)
    }

    @Test fun allLocalSatellitesAreAvailableAndSelectedOnesAreFirstWithoutAutoCalculation() = runTest {
        val predictor = Predictor()
        val vm = model(predictor)
        advanceUntilIdle()
        assertEquals(listOf(2, 1), vm.uiState.value.satellites.map { it.catnum })
        assertEquals(2, vm.uiState.value.catalogNumber)
        assertTrue(predictor.requests.isEmpty())
        assertEquals("30", vm.uiState.value.days)
    }

    @Test fun repeatedPredictAndLateProgressCannotOverwriteNewRequest() = runTest {
        val predictor = Predictor()
        val vm = model(predictor)
        advanceUntilIdle()
        vm.onAction(SunlightAction.Predict)
        advanceUntilIdle()
        vm.onAction(SunlightAction.Predict)
        advanceUntilIdle()
        assertEquals(2, predictor.requests.size)
        predictor.callbacks[1](0.2)
        predictor.callbacks[0](0.9)
        assertEquals(0.2, vm.uiState.value.progress, 0.0)
        val result = result(predictor.requests[1])
        predictor.completions[1].complete(result)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.calculating)
        assertEquals(result, vm.uiState.value.prediction)
    }

    @Test fun filtersSortingAndTimezoneDoNotPropagateAgainOrModifyStatistics() = runTest {
        val predictor = Predictor()
        val utc = MutableStateFlow(false)
        val vm = model(predictor, utc = utc)
        advanceUntilIdle()
        vm.onAction(SunlightAction.Predict)
        advanceUntilIdle()
        val result = result(predictor.requests.single())
        predictor.completions.single().complete(result)
        advanceUntilIdle()
        vm.onAction(SunlightAction.Filter(Illumination.SUNLIGHT, 2, SunlightSort.DURATION))
        utc.value = true
        advanceUntilIdle()
        assertEquals(1, predictor.requests.size)
        assertEquals(1, vm.uiState.value.visibleIntervals.size)
        assertEquals(result, vm.uiState.value.prediction)
        assertTrue(vm.uiState.value.displayUtc)
        assertEquals(2, result.sunlightCount)
    }

    @Test fun inputEditCancelsAndInvalidatesOldResultsAndInvalidDaysNeverStart() = runTest {
        val predictor = Predictor()
        val vm = model(predictor)
        advanceUntilIdle()
        vm.onAction(SunlightAction.Predict)
        advanceUntilIdle()
        vm.onAction(SunlightAction.SetDays("366"))
        predictor.callbacks.single()(0.8)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.calculating)
        assertNull(vm.uiState.value.prediction)
        vm.onAction(SunlightAction.Predict)
        advanceUntilIdle()
        assertEquals(1, predictor.requests.size)
        assertFalse(vm.uiState.value.daysValid)
    }

    @Test fun cancellationIsVisibleAndSavedInputsRestoreWithoutRestoringAWorker() = runTest {
        val predictor = Predictor()
        val saved = SavedStateHandle()
        val vm = model(predictor, saved)
        advanceUntilIdle()
        vm.onAction(SunlightAction.SetDays("365"))
        vm.onAction(SunlightAction.SelectSatellite(1))
        vm.onAction(SunlightAction.Filter(null, 12, SunlightSort.DURATION))
        vm.onAction(SunlightAction.Predict)
        advanceUntilIdle()
        vm.onAction(SunlightAction.Cancel)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.cancelled)
        assertFalse(vm.uiState.value.calculating)
        val restored = model(predictor, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        advanceUntilIdle()
        assertEquals("365", restored.uiState.value.days)
        assertEquals(1, restored.uiState.value.catalogNumber)
        assertEquals(12L, restored.uiState.value.minimumMinutes)
        assertNull(restored.uiState.value.filter)
        assertFalse(restored.uiState.value.calculating)
        assertNull(restored.uiState.value.prediction)
        assertEquals(1, predictor.requests.size)
    }

    @Test fun utcDateAnchorsAndLocalDstFormattingRemainUnambiguous() {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val first = requireNotNull(utc.parse("2026-11-01T05:30:00")).time
        val second = requireNotNull(utc.parse("2026-11-01T06:30:00")).time
        val local = resultTimeFormatter(Locale.US, TimeZone.getTimeZone("America/New_York"))
        assertTrue(local.format(Date(first)) != local.format(Date(second)))
        assertEquals(utcDayStart(first), utcDayStart(second))
        assertEquals(0L, utcDayStart(second) % 86_400_000L)
    }

    private fun result(request: SunlightRequest): SunlightPrediction {
        val start = request.startUtcMillis
        val elements = OrbitalData("Test", 26316.0, 14.0, 0.01, 50.0, 0.0, 0.0, 0.0, request.catalogNumber, 0.0)
        return SunlightPrediction(request, elements, listOf(
            IlluminationInterval(Illumination.SUNLIGHT, start, start + 60_000),
            IlluminationInterval(Illumination.PENUMBRA, start + 60_000, start + 180_000),
            IlluminationInterval(Illumination.SUNLIGHT, start + 180_000, request.endUtcMillis)
        ), request.endUtcMillis)
    }
}
