/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.injection

import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.location.LocationManager
import androidx.room.Room
import com.rtbishop.look4sat.core.data.database.Look4SatDb
import com.rtbishop.look4sat.core.data.database.QsoDatabase
import com.rtbishop.look4sat.core.data.framework.BluetoothReporter
import com.rtbishop.look4sat.core.data.framework.AndroidRadioTransportFactory
import com.rtbishop.look4sat.core.data.framework.NetworkReporter
import com.rtbishop.look4sat.core.data.framework.RadioTrackingService
import com.rtbishop.look4sat.core.data.ft4.Ft4Service
import com.rtbishop.look4sat.core.data.ft4.Ft4AudioTransmitter
import com.rtbishop.look4sat.core.data.repository.AmSatRepository
import com.rtbishop.look4sat.core.data.repository.DatabaseRepo
import com.rtbishop.look4sat.core.data.repository.QsoRepository
import com.rtbishop.look4sat.core.data.repository.SatelliteRepo
import com.rtbishop.look4sat.core.data.repository.SelectionRepo
import com.rtbishop.look4sat.core.data.repository.SensorsRepo
import com.rtbishop.look4sat.core.data.repository.SettingsRepo
import com.rtbishop.look4sat.core.data.repository.UpdateRepository
import com.rtbishop.look4sat.core.data.source.LocalSource
import com.rtbishop.look4sat.core.data.source.RemoteSource
import com.rtbishop.look4sat.core.data.usecase.AddToCalendar
import com.rtbishop.look4sat.core.data.usecase.SharedAudioHub
import com.rtbishop.look4sat.core.data.time.AndroidMonotonicTimeSource
import com.rtbishop.look4sat.core.data.time.AndroidTimeSynchronizationService
import com.rtbishop.look4sat.core.data.usecase.SaveImage
import com.rtbishop.look4sat.core.data.usecase.ShowToast
import com.rtbishop.look4sat.core.domain.audio.IAudioHub
import com.rtbishop.look4sat.core.domain.ft4.IFt4Service
import com.rtbishop.look4sat.core.domain.logbook.IQsoRepository
import com.rtbishop.look4sat.core.domain.ft4.IFt4TransmitCoordinator
import com.rtbishop.look4sat.core.domain.ft4.IFt4AudioTransmitter
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import com.rtbishop.look4sat.core.domain.time.ITimeSynchronizationService
import com.rtbishop.look4sat.core.domain.time.SystemDisciplinedClock
import com.rtbishop.look4sat.core.domain.repository.IDatabaseRepo
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.IRadioTrackingService
import com.rtbishop.look4sat.core.domain.repository.IReporter
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISelectionRepo
import com.rtbishop.look4sat.core.domain.repository.ISensorsRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.MutualPassData
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import com.rtbishop.look4sat.core.domain.usecase.IAddToCalendar
import com.rtbishop.look4sat.core.domain.usecase.ISaveImage
import com.rtbishop.look4sat.core.domain.usecase.IShowToast
import com.rtbishop.look4sat.core.domain.utility.DataParser
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

class MainContainer(private val context: Context) : IMainContainer {

    private val localSource = provideLocalSource()
    private val remoteSource = provideRemoteSource()
    private val mainHandler = CoroutineExceptionHandler { _, error -> println("MainHandler: $error") }
    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + mainHandler)
    override val settingsRepo = provideSettingsRepo()
    override val selectionRepo = provideSelectionRepo()
    override val satelliteRepo = provideSatelliteRepo()
    override val databaseRepo = provideDatabaseRepo()
    override val qsoRepository: IQsoRepository by lazy {
        val database = Room.databaseBuilder(context, QsoDatabase::class.java, "Look4SatQsoDB").build()
        QsoRepository(database.qsoDao(), Dispatchers.IO)
    }
    override val amSatRepo by lazy { AmSatRepository(remoteSource, appScope) }
    override val updateRepo by lazy { UpdateRepository(remoteSource) }
    override val audioHub: IAudioHub by lazy {
        SharedAudioHub(
            context = context,
            scope = appScope,
            initialDeviceKey = settingsRepo.ft4Settings.value.audioInputDeviceKey.ifBlank { null }
        )
    }
    override val ft4Service: IFt4Service by lazy { Ft4Service(appScope, audioHub, disciplinedClock) }
    override val disciplinedClock: IDisciplinedClock by lazy {
        SystemDisciplinedClock(AndroidMonotonicTimeSource)
    }
    override val timeSynchronizationService: ITimeSynchronizationService =
        AndroidTimeSynchronizationService(context, appScope, disciplinedClock).also { service ->
            val settings = settingsRepo.ft4Settings.value
            service.setNtpEnabled(settings.ntpSynchronizationEnabled)
            service.setGnssEnabled(settings.gnssSynchronizationEnabled)
        }
    private val sharedRadioTrackingService: RadioTrackingService by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val transportFactory = AndroidRadioTransportFactory(context, manager)
        RadioTrackingService(
            appScope,
            manager,
            satelliteRepo,
            settingsRepo,
            disciplinedClock,
            transportFactory = transportFactory::create
        )
    }
    override val radioTrackingService: IRadioTrackingService by lazy { sharedRadioTrackingService }
    override val ft4TransmitCoordinator: IFt4TransmitCoordinator by lazy { sharedRadioTrackingService }
    override val ft4AudioTransmitter: IFt4AudioTransmitter by lazy {
        Ft4AudioTransmitter(context, ft4Service, ft4TransmitCoordinator, disciplinedClock)
    }

    private val _mutualPassData = MutableStateFlow(MutualPassData())
    override val mutualPassData: StateFlow<MutualPassData> = _mutualPassData.asStateFlow()

    override fun setMutualPassData(data: MutualPassData) {
        _mutualPassData.value = data
    }

    override fun provideAddToCalendar(): IAddToCalendar = AddToCalendar(context)

    override fun provideShowToast(): IShowToast = ShowToast(context)

    override fun provideSaveImage(): ISaveImage = SaveImage(context)

    override fun provideBluetoothReporter(): IReporter {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val rc = settingsRepo.rcSettings.value
        return BluetoothReporter(
            manager,
            CoroutineScope(Dispatchers.IO),
            rc.bluetoothRotatorAddress,
            rc.bluetoothFrequencyAddress
        )
    }

    override fun provideNetworkReporter(): IReporter {
        val rc = settingsRepo.rcSettings.value
        return NetworkReporter(
            CoroutineScope(Dispatchers.IO),
            rc.rotatorAddress,
            rc.rotatorPort.toIntOrNull() ?: 0,
            rc.frequencyAddress,
            rc.frequencyPort.toIntOrNull() ?: 0,
            rc.frequencyOffsetHz
        )
    }

    override fun provideSensorsRepo(): ISensorsRepo {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val displayManager = context.getSystemService(DisplayManager::class.java)
        return SensorsRepo(manager, displayManager)
    }

    private fun provideDatabaseRepo(): IDatabaseRepo {
        val dbDispatcher = Dispatchers.Default
        val dataParser = DataParser(dbDispatcher)
        val remoteSource = provideRemoteSource()
        return DatabaseRepo(dbDispatcher, dataParser, localSource, remoteSource, settingsRepo)
    }

    private fun provideLocalSource(): ILocalSource {
        val builder = Room.databaseBuilder(context, Look4SatDb::class.java, "Look4SatDBv400")
        val database = builder.fallbackToDestructiveMigration(false).build()
        return LocalSource(database.look4SatDao())
    }

    private fun provideRemoteSource(): IRemoteSource {
        return RemoteSource(Dispatchers.IO, context.contentResolver, OkHttpClient.Builder().build())
    }

    private fun provideSatelliteRepo(): ISatelliteRepo {
        return SatelliteRepo(Dispatchers.Default, localSource, settingsRepo)
    }

    private fun provideSelectionRepo(): ISelectionRepo {
        return SelectionRepo(Dispatchers.Default, localSource, settingsRepo)
    }

    private fun provideSettingsRepo(): ISettingsRepo {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val appPrefsFileName = "${context.packageName}_preferences"
        val appPreferences = context.getSharedPreferences(appPrefsFileName, Context.MODE_PRIVATE)
        val appVersionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "4.0.4"
        return SettingsRepo(manager, appPreferences, appVersionName)
    }
}
