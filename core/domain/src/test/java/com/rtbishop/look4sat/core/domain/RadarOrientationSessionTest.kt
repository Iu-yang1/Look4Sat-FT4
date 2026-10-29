package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadarOrientationSessionTest {
    private class Sensors: ISensorsRepo {
        override val sensorData=MutableStateFlow(0f to 0f)
        override val compassAccuracy=MutableStateFlow(CompassAccuracy.HIGH)
        var starts=0;var stops=0
        override fun enableSensor() { starts++ }
        override fun disableSensor() { stops++ }
        override fun getMagDeclination(geoPos: GeoPos,time: Long)=geoPos.longitude.toFloat()
    }
    @Test fun foregroundFollowingWorksWithoutAudioAndRestartsAfterBackground()=runTest {
        val sensors=Sensors();val enabled=MutableStateFlow(true);val offset=MutableStateFlow(5f)
        val station=MutableStateFlow(GeoPos(0.0,10.0))
        val session=RadarOrientationSession(backgroundScope,sensors,enabled,offset,station)
        runCurrent();assertEquals(0,sensors.starts)
        session.setForeground(true);runCurrent();assertEquals(1,sensors.starts)
        sensors.sensorData.value=350f to -20f;runCurrent()
        assertEquals(5f to -20f,session.state.value.angles);assertTrue(session.state.value.enabled)
        // Calibration and location changes update heading without resetting smoothing/listeners.
        offset.value=-25f;runCurrent();assertEquals(335f,session.state.value.angles.first)
        station.value=GeoPos(0.0,20.0);runCurrent();assertEquals(345f,session.state.value.angles.first)
        assertEquals(1,sensors.starts)
        session.setForeground(false);runCurrent();assertEquals(1,sensors.stops)
        session.setForeground(true);runCurrent();assertEquals(2,sensors.starts)
        sensors.sensorData.value=60f to 30f;runCurrent();assertEquals(55f to 30f,session.state.value.angles)
        session.close();runCurrent();assertEquals(2,sensors.stops)
    }
    @Test fun disablingCompassOrMissingSensorDoesNotPretendToTrack()=runTest {
        val sensors=Sensors();val enabled=MutableStateFlow(true)
        val session=RadarOrientationSession(backgroundScope,sensors,enabled,MutableStateFlow(0f),MutableStateFlow(GeoPos(0.0,0.0)))
        session.setForeground(true);runCurrent()
        sensors.compassAccuracy.value=CompassAccuracy.UNAVAILABLE;runCurrent();assertFalse(session.state.value.enabled)
        enabled.value=false;runCurrent();assertEquals(1,sensors.stops)
        enabled.value=true;runCurrent();assertEquals(2,sensors.starts)
        session.setForeground(true);runCurrent();assertEquals(2,sensors.starts)
        session.close();runCurrent()
    }
}
