package com.rtbishop.look4sat.core.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RadioBaseFrequencySettingsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val prefs=context.getSharedPreferences("reference-tests",Context.MODE_PRIVATE)
    private fun repository()=SettingsRepo(context.getSystemService(LocationManager::class.java),prefs,"test")

    @Test fun newRepositoryRestoresBothReferencesWithoutChangingCalculatorOffset() {
        val first=repository()
        first.setSatelliteOffset(12345,"2.5")
        val expected=RadioBaseFrequencies(435057000,145962000)
        assertTrue(first.setRadioBaseFrequencies("linear",expected))
        val reopened=repository()
        assertEquals(expected,reopened.getRadioBaseFrequencies("linear"))
        assertEquals("2.5",reopened.getSatelliteOffset(12345))
    }

    @Test fun transmitDisabledAndDifferentTranspondersSurviveReloadIndependently() {
        val first=RadioBaseFrequencies(435100000,null)
        val second=RadioBaseFrequencies(145800000,437800000)
        assertTrue(repository().setRadioBaseFrequencies("rx-only",first))
        assertTrue(repository().setRadioBaseFrequencies("other",second))
        assertEquals(first,repository().getRadioBaseFrequencies("rx-only"))
        assertEquals(second,repository().getRadioBaseFrequencies("other"))
        assertNull(repository().getRadioBaseFrequencies("missing"))
    }

    @Test fun malformedAndWrongTypePreferencesFallBackWithoutInventingTransmitFrequency() {
        for(text in listOf("invalid","{}","{\"version\":2,\"rxHz\":435000000,\"txHz\":null}",
            "{\"version\":1,\"rxHz\":435000000}","{\"version\":1,\"rxHz\":1.5,\"txHz\":null}",
            "{\"version\":1,\"rxHz\":0,\"txHz\":145950000}")) {
            prefs.edit().putString("radioBaseFrequencies.v1.bad",text).commit()
            assertNull(readRadioBaseFrequencies(prefs,"bad"))
        }
        prefs.edit().putInt("radioBaseFrequencies.v1.bad",1).commit()
        assertNull(readRadioBaseFrequencies(prefs,"bad"))
    }

    @Test fun invalidWriteCannotOverwriteThePreviouslyAcceptedPair() {
        val expected=RadioBaseFrequencies(435057000,145962000)
        assertTrue(writeRadioBaseFrequencies(prefs,"linear",expected))
        assertThrows(IllegalArgumentException::class.java) {
            writeRadioBaseFrequencies(prefs,"linear",RadioBaseFrequencies(0,145950000))
        }
        assertEquals(expected,readRadioBaseFrequencies(prefs,"linear"))
    }

    @Test fun diskWriteFailureIsReportedToTheCaller() {
        val editor=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { proxy,method,_ ->
            when(method.name) { "putString"->proxy;"commit"->false;else->error(method.name) }
        } as SharedPreferences.Editor
        val broken=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences::class.java)) { _,method,_ ->
            if(method.name=="edit") editor else error(method.name)
        } as SharedPreferences
        assertFalse(writeRadioBaseFrequencies(broken,"linear",RadioBaseFrequencies(435057000,145962000)))
    }
}
