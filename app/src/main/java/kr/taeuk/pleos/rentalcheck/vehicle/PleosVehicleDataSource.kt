package kr.taeuk.pleos.rentalcheck.vehicle

import android.content.Context
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import java.util.Locale
import kr.taeuk.pleos.rentalcheck.model.ReadStatus
import kr.taeuk.pleos.rentalcheck.model.VehicleReading
import kr.taeuk.pleos.rentalcheck.model.VehicleSnapshot
import kr.taeuk.pleos.rentalcheck.model.WindowPositionContract
import kr.taeuk.pleos.rentalcheck.model.SeatOccupancy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private typealias CapRequest = ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit
private typealias NumberRequest = ((Double?) -> Unit, (Exception) -> Unit) -> Unit

/** Real SDK calls only. Unsupported/failed properties never fall back to demo values. */
class PleosVehicleDataSource(context: Context) : VehicleDataSource, InteractionObserver {
    private val vehicle = Vehicle(context.applicationContext)
    private val rawSeats = RawSeatTransport(context.applicationContext)
    private val snapshotMutex = Mutex()
    private var initialized = false
    private var stopInteractions: (() -> Unit)? = null

    override fun initialize() {
        if (!initialized) {
            vehicle.initialize()
            rawSeats.initialize()
            initialized = true
        }
    }

    // Collect independently guarded SDK reads without turning unavailable signals into normal vehicle states.
    override suspend fun readSnapshot(): VehicleSnapshot = snapshotMutex.withLock {
        check(initialized) { "Vehicle SDK가 초기화되지 않았습니다." }
        val started = System.currentTimeMillis()
        val readings = supervisorScope {
            listOf(
                async { readVin() },
                async { readGear() },
                async {
                    readNumber("fuel.percent", "연료 잔량", "%", 0.0..100.0,
                        check = { ok, fail -> vehicle.getFuel().checkFuelPercentCapabilities(ok, fail) },
                        get = { ok, fail -> vehicle.getFuel().getFuelPercent({ ok(it?.toDouble()) }, fail) })
                },
                async {
                    readNumber("battery.percent", "배터리 충전율", "%", 0.0..100.0,
                        check = { ok, fail -> vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities(ok, fail) },
                        get = { ok, fail -> vehicle.getEvBattery().getEvBatteryStateOfCharge({ ok(it?.toDouble()) }, fail) })
                },
                async {
                    // This specific SDK method returns km; trip-distance APIs return meters.
                    readNumber("odometer.km", "누적 주행거리", "km", 0.0..Double.MAX_VALUE,
                        check = { ok, fail -> vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities(ok, fail) },
                        get = { ok, fail -> vehicle.getOdometer().getCurrentDistanceDriven({ ok(it?.toDouble()) }, fail) })
                },
                async { readDoors() },
                async { readDoorLocks() },
                async { readWindows() },
                async { readSeatOccupancy() },
                async { readTireThresholds() },
                async { readWarnings() },
            ).awaitAll().flatten()
        }
        VehicleSnapshot(started, System.currentTimeMillis(), readings.sortedBy { it.key })
    }

    @Suppress("DEPRECATION")
    private suspend fun readGear(): List<VehicleReading> = guarded("gear.selection", "현재 기어") {
        // SDK 2.3.2 exposes this public CAR_DRIVE getter; require a real P response before offering the stationary exercise.
        val gear = sdkAwait<ai.pleos.playground.vehicle.constant.control.VehicleGear?> { ok, fail ->
            vehicle.getGear().getCurrentGearSelectionState(ok, fail)
        }
        listOf(if (gear == null) unavailable("gear.selection", "현재 기어", "기어를 수신하지 못했습니다.")
            else value("gear.selection", "현재 기어", gear.name, gear.name.removePrefix("GEAR_")))
    }

    override suspend fun observeInteractions(onEvent: (VehicleInteraction) -> Unit): Closeable {
        // Match each listener with its original area, reject late callbacks after cleanup, and never request vehicle control permissions.
        check(initialized)
        stopInteractions?.invoke()
        val active = AtomicBoolean(true)
        val cleanup = mutableListOf<() -> Unit>()
        val close = {
            if (active.compareAndSet(true, false)) { cleanup.asReversed().forEach { runCatching(it) }; cleanup.clear() }
            Unit
        }
        stopInteractions = close
        try {
            try {
                val domain = vehicle.getDoor()
                val (config, areas) = sdkAwait<Pair<CapabilityConfig, Map<DoorArea, AreaBounds<Int>>>> { ok, fail ->
                    domain.checkDoorPositionCapabilities({ c, map -> ok(c to map) }, fail)
                }
                ensureReadable(config)
                for ((area, bounds) in areas) {
                    val key = "door.${area.values.map { it.name }.sorted().joinToString("+")}.position"
                    if (kr.taeuk.pleos.rentalcheck.model.StorageSpace.entries.none { it.doorKey == key }) continue
                    val listener = object : DoorPositionListener {
                        // Preserve the observed raw value and its capability bounds for conservative location tracking.
                        override fun onPositionUpdated(position: Int?) { if (active.get()) onEvent(VehicleInteraction(key, position, bounds.minValue, bounds.maxValue,
                            if (position == null) ReadStatus.UNAVAILABLE else ReadStatus.AVAILABLE)) }
                        // A failed listener must be visible as a coverage gap, never as a closed or unused compartment.
                        override fun onFailed(error: Exception) { if (active.get()) onEvent(VehicleInteraction(key, null, status = ReadStatus.ERROR, detail = error.message.orEmpty())) }
                    }
                    cleanup += { domain.unregisterDoorPosition(area, listener) }
                    domain.registerDoorPosition(area, listener)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (active.get()) onEvent(VehicleInteraction("door", null, status = ReadStatus.ERROR, detail = error.message.orEmpty())) }
            try {
                val domain = vehicle.getWindow()
                val (config, areas) = sdkAwait<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>> { ok, fail ->
                    domain.checkWindowPositionCapabilities({ c, map -> ok(c to map) }, fail)
                }
                ensureReadable(config)
                for ((area, bounds) in areas) {
                    val key = "window.${area.values.map { it.name }.sorted().joinToString("+")}.position"
                    val listener = object : WindowPositionListener {
                        // Signal a changed window, but leave practice completion to a new bounded SDK read instead of trusting queued callbacks.
                        override fun onWindowPositionUpdated(position: Int?) { if (active.get()) onEvent(VehicleInteraction(key, position, bounds.minValue, bounds.maxValue,
                            if (position == null) ReadStatus.UNAVAILABLE else ReadStatus.AVAILABLE)) }
                        // Retain subscription errors so the UI can describe incomplete monitoring without inventing a reading.
                        override fun onFailed(error: Exception) { if (active.get()) onEvent(VehicleInteraction(key, null, status = ReadStatus.ERROR, detail = error.message.orEmpty())) }
                    }
                    cleanup += { domain.unregisterWindowPosition(area, listener) }
                    domain.registerWindowPosition(area, listener)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (active.get()) onEvent(VehicleInteraction("window", null, status = ReadStatus.ERROR, detail = error.message.orEmpty())) }
            try {
                val domain = vehicle.getSeat()
                for (area in seatAreas()) {
                    val names = area.values.map { it.name }
                    val key = "seat.${names.sorted().joinToString("+")}.occupancy"
                    val label = "${areaLabel(names)} 좌석 점유"
                    val areaId = area.values.fold(0) { id, value -> id or value.id }
                    val handle = rawSeats.observe(areaId, { raw ->
                        val reading = SeatOccupancy.fromRaw(key, label, raw, System.currentTimeMillis()).last()
                        if (active.get()) onEvent(VehicleInteraction(key, null, status = reading.status, reading = reading))
                    }, { error -> if (active.get()) onEvent(VehicleInteraction(key, null, reading = failure(key, label, error))) })
                    cleanup += { handle.close() }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (active.get()) onEvent(VehicleInteraction("seat.all.occupancy", null,
                    reading = failure("seat.all.occupancy", "좌석 점유", error)))
            }
            return Closeable { close() }
        } catch (error: Exception) { close(); throw error }
    }

    // Query supported readable seat areas before either fetching values or subscribing; never assume four seats on a real vehicle.
    private suspend fun seatAreas(): List<SeatArea> {
        val (config, areas) = sdkAwait<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            vehicle.getSeat().checkSeatOccupancyCapabilities({ c, list -> ok(c to list) }, fail)
        }
        ensureReadable(config); ensureAreas(areas)
        return areas
    }

    // Include independently timestamped real seat signals in normal pickup/return snapshots, preserving unsupported and failed reads.
    private suspend fun readSeatOccupancy(): List<VehicleReading> = guarded("seat.all.occupancy", "좌석 점유") {
        supervisorScope {
            seatAreas().map { area -> async {
                val names = area.values.map { it.name }
                val key = "seat.${names.sorted().joinToString("+")}.occupancy"
                val label = "${areaLabel(names)} 좌석 점유"
                guarded(key, label) {
                    val sdk = guarded(key.removeSuffix(".occupancy") + ".sdkOccupancy", "$label SDK 원본") {
                        val occupied = sdkAwait<Boolean?> { ok, fail -> vehicle.getSeat().getCurrentSeatOccupancy(area, ok, fail) }
                        listOf(SeatOccupancy.reading(key.removeSuffix(".occupancy") + ".sdkOccupancy", "$label SDK 원본", occupied, System.currentTimeMillis()))
                    }
                    val normalized = guarded(key, label) {
                        val raw = rawSeats.read(area.values.fold(0) { id, value -> id or value.id })
                        SeatOccupancy.fromRaw(key, label, raw, System.currentTimeMillis())
                    }
                    sdk + normalized
                }
            } }.awaitAll().flatten()
        }
    }

    private suspend fun readNumber(
        key: String, label: String, unit: String, range: ClosedFloatingPointRange<Double>,
        check: CapRequest, get: NumberRequest,
    ): List<VehicleReading> = guarded(key, label) {
        ensureReadable(sdkAwait(request = check))
        val number = sdkAwait(request = get)
            ?: return@guarded listOf(unavailable(key, label, "SDK가 null을 반환했습니다."))
        require(number.isFinite() && number in range) { "유효 범위를 벗어난 SDK 값: $number" }
        listOf(value(key, label, number.toString(), "${format(number)} $unit", unit))
    }

    private suspend fun readVin(): List<VehicleReading> = guarded("vehicle.vin", "차량 식별 번호(VIN)") {
        val info = vehicle.getCarInfo()
        ensureReadable(sdkAwait { ok, fail -> info.checkVINInfoCapabilities(ok, fail) })
        val raw = sdkAwait<String?> { ok, fail -> info.getVINInfo(ok, fail) }
        val vin = kr.taeuk.pleos.rentalcheck.model.VehicleIdentity.normalize(raw)
            ?: return@guarded listOf(unavailable("vehicle.vin", "차량 식별 번호(VIN)", "유효한 VIN을 수신하지 못했습니다."))
        listOf(value("vehicle.vin", "차량 식별 번호(VIN)", vin, "VIN ···${vin.takeLast(6)}"))
    }

    private suspend fun readDoors(): List<VehicleReading> = guarded("door", "문 개폐 상태") {
        val domain = vehicle.getDoor()
        val (config, areas) = sdkAwait<Pair<CapabilityConfig, Map<DoorArea, AreaBounds<Int>>>> { ok, fail ->
            domain.checkDoorPositionCapabilities({ c, map -> ok(c to map) }, fail)
        }
        ensureReadable(config)
        ensureAreas(areas.keys)
        supervisorScope {
            areas.entries.map { (area, bounds) -> async {
                val names = area.values.map { it.name }
                val key = "door.${names.sorted().joinToString("+")}.position"
                val label = "${areaLabel(names)} 문"
                guarded(key, label) {
                    val n = sdkAwait<Int?> { ok, fail -> domain.getCurrentDoorPosition(area, ok, fail) }
                    val raw = position(key, label, n, bounds, isDoor = true)
                    val closed = WindowPositionContract.closed(n, bounds.minValue, bounds.maxValue)
                    listOf(raw, if (closed == null) unavailable(key.removeSuffix(".position") + ".closed", "$label 닫힘 여부", "문 범위 또는 현재값을 확인할 수 없습니다.")
                        else value(key.removeSuffix(".position") + ".closed", "$label 닫힘 여부", closed.toString(), if (closed) "닫힘" else "열림"))
                }
            } }.awaitAll().flatten()
        }
    }

    private suspend fun readDoorLocks(): List<VehicleReading> = guarded("doorLock", "문 잠금 상태") {
        val domain = vehicle.getDoor()
        val (config, areas) = sdkAwait<Pair<CapabilityConfig, List<DoorArea>>> { ok, fail ->
            domain.checkDoorLockCapabilities({ c, list -> ok(c to list) }, fail)
        }
        ensureReadable(config)
        ensureAreas(areas)
        supervisorScope {
            areas.map { area -> async {
                val names = area.values.map { it.name }
                val key = "door.${names.sorted().joinToString("+")}.lock"
                val label = "${areaLabel(names)} 잠금"
                guarded(key, label) {
                    val locked = sdkAwait<Boolean?> { ok, fail -> domain.getDoorLockState(area, ok, fail) }
                    listOf(if (locked == null) unavailable(key, label, "SDK가 null을 반환했습니다.")
                        else value(key, label, locked.toString(), if (locked) "잠김" else "잠금 해제"))
                }
            } }.awaitAll().flatten()
        }
    }

    private suspend fun readWindows(): List<VehicleReading> = guarded("window", "창문 위치") {
        val domain = vehicle.getWindow()
        val (config, areas) = sdkAwait<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>> { ok, fail ->
            domain.checkWindowPositionCapabilities({ c, map -> ok(c to map) }, fail)
        }
        ensureReadable(config)
        ensureAreas(areas.keys)
        supervisorScope {
            areas.entries.map { (area, bounds) -> async {
                val names = area.values.map { it.name }
                val key = "window.${names.sorted().joinToString("+")}.position"
                val label = "${areaLabel(names)} 창문"
                guarded(key, label) {
                    val n = sdkAwait<Int?> { ok, fail -> domain.getCurrentWindowPosition(area, ok, fail) }
                    val raw = position(key, label, n, bounds, isDoor = false)
                    val closed = WindowPositionContract.closed(n, bounds.minValue, bounds.maxValue)
                    val closureKey = key.removeSuffix(".position") + ".closed"
                    val closure = if (closed == null) unavailable(closureKey, "$label 닫힘 여부",
                        "위치값 또는 지원 범위로 닫힘을 판단할 수 없습니다. 직접 확인해 주세요.")
                    else value(closureKey, "$label 닫힘 여부", closed.toString(), if (closed) "닫힘" else "열림",
                        detail = "WINDOW_POS 지원 범위 0 ~ ${bounds.maxValue}; 0은 닫힘, 양수는 열림. 원시 위치 값 $n")
                    listOf(raw, closure)
                }
            } }.awaitAll().flatten()
        }
    }

    private suspend fun readTireThresholds(): List<VehicleReading> = guarded("tire", "타이어 저압 기준") {
        val domain = vehicle.getTire()
        val (config, areas) = sdkAwait<Pair<CapabilityConfig, List<WheelArea>>> { ok, fail ->
            domain.checkLowTirePressureCapabilities({ c, list -> ok(c to list) }, fail)
        }
        ensureReadable(config)
        ensureAreas(areas)
        supervisorScope {
            areas.map { area -> async {
                val names = area.values.map { it.name }
                val key = "tire.${names.sorted().joinToString("+")}.lowThresholdKpa"
                val label = "${areaLabel(names)} 저압 기준"
                guarded(key, label) {
                    // This is a threshold, NOT current measured pressure.
                    // getTirePressure() needs PRIVATE_CAR_SAFETY and is deliberately not used.
                    val n = sdkAwait<Float?> { ok, fail -> domain.getLowTirePressure(area, ok, fail) }
                    listOf(if (n == null) unavailable(key, label, "SDK가 null을 반환했습니다.") else {
                        require(n.isFinite() && n >= 0) { "유효하지 않은 저압 기준: $n" }
                        value(key, label, n.toString(), "${format(n.toDouble())} kPa", "kPa",
                            "현재 공기압이 아닌 차량이 제공한 저압 판단 기준값입니다.")
                    })
                }
            } }.awaitAll().flatten()
        }
    }

    private suspend fun readWarnings(): List<VehicleReading> = guarded("safety", "차량 경고 표시") {
        val domain = vehicle.getSafety()
        // SDK 2.3.2 exposes a public CAR_SAFETY getter, but its capability check
        // requires PRIVATE_CAR_SAFETY. Do not request private privileges merely
        // for preflight; call the public getter and preserve errors as errors.
        val result = sdkAwait<Telltales> { ok, fail -> domain.getTelltalesInfo(ok, fail) }
        val active = result.telltales.filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }
        // Headlamps, turn signals, drive-mode and other ordinary indicators are not faults.
        val warnings = active.filter { it.type.name in warningTypes }.map { it.type.name }.distinct().sorted()
        val tpms = result.telltales.firstOrNull { it.type.name == "TPMS" }
        listOf(
            value("safety.activeWarnings", "수신된 경고 표시", warnings.joinToString("|"),
                if (warnings.isEmpty()) "수신된 경고 없음" else warnings.joinToString(", "),
                detail = "수신된 계기판 표시 중 지정된 경고 종류를 집계합니다. 차량 결함 진단 결과가 아닙니다."),
            if (tpms == null) unavailable("safety.tpms", "타이어 경고(TPMS)", "수신 목록에 TPMS 항목이 없습니다.")
            else value("safety.tpms", "타이어 경고(TPMS)", tpms.state.name,
                when (tpms.state) { TelltaleState.OFF -> "꺼짐"; TelltaleState.ON -> "켜짐"; else -> "점멸" }),
        )
    }

    private fun position(
        key: String, label: String, n: Int?, bounds: AreaBounds<Int>, isDoor: Boolean,
    ): VehicleReading {
        if (n == null) return unavailable(key, label, "SDK가 null을 반환했습니다.")
        require(n >= bounds.minValue && n <= bounds.maxValue) { "SDK 값 $n / 허용 범위 $bounds" }
        val display = when {
            isDoor && n == 0 -> "닫힘"
            isDoor && n == 1 -> "열림"
            else -> "위치 값 $n"
        }
        // Do not invent a percentage or assume that the minimum equals "closed".
        return value(key, label, n.toString(), display,
            detail = "지원 범위 ${bounds.minValue} ~ ${bounds.maxValue}; 원시 위치 값 $n")
    }

    private fun ensureReadable(config: CapabilityConfig) {
        if (config.accessType != AccessType.READ && config.accessType != AccessType.READ_WRITE)
            throw UnsupportedProperty("현재 환경에서 읽기 지원 안 함: ${config.accessType}")
    }

    private fun ensureAreas(areas: Collection<*>) {
        if (areas.isEmpty()) throw UnsupportedProperty("SDK가 지원 영역을 반환하지 않았습니다.")
    }

    // Isolate one property failure so unrelated vehicle readings remain usable while the failed status stays explicit.
    private suspend fun guarded(
        key: String, label: String, block: suspend () -> List<VehicleReading>,
    ): List<VehicleReading> = try {
        block()
    } catch (e: TimeoutCancellationException) {
        listOf(VehicleReading.missing(key, label, ReadStatus.TIMED_OUT, "응답 시간 초과(4초)"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnsupportedProperty) {
        listOf(VehicleReading.missing(key, label, ReadStatus.UNSUPPORTED, e.message.orEmpty()))
    } catch (e: Exception) {
        listOf(failure(key, label, e))
    }

    // Use the same failure classification for snapshots and live subscriptions, retaining permission and support failures explicitly.
    private fun failure(key: String, label: String, error: Exception): VehicleReading {
        val permissionDenied = generateSequence<Throwable>(error) { it.cause }.any {
            it is SecurityException || it.message.orEmpty().contains("permission", ignoreCase = true)
        }
        val status = when {
            error is TimeoutCancellationException -> ReadStatus.TIMED_OUT
            error is UnsupportedProperty -> ReadStatus.UNSUPPORTED
            permissionDenied -> ReadStatus.PERMISSION_DENIED
            else -> ReadStatus.ERROR
        }
        return VehicleReading.missing(key, label, status, "${error.javaClass.simpleName}: ${error.message.orEmpty().take(240)}")
            .copy(observedAtMillis = System.currentTimeMillis())
    }

    private fun value(
        key: String, label: String, raw: String, display: String, unit: String? = null, detail: String = "",
    ) = VehicleReading(key, label, ReadStatus.AVAILABLE, raw, display, unit, detail, System.currentTimeMillis())

    private fun unavailable(key: String, label: String, detail: String) =
        VehicleReading.missing(key, label, ReadStatus.UNAVAILABLE, detail)

    override fun close() {
        // Unregister before releasing the SDK so foreground-only observers cannot update another rental after stopping.
        stopInteractions?.invoke(); stopInteractions = null
        rawSeats.close()
        if (initialized) {
            vehicle.release()
            initialized = false
        }
    }

    private class UnsupportedProperty(message: String) : Exception(message)

    companion object {
        private val warningTypes = setOf(
            "ABS", "MDPS", "TPMS", "LOW_VOLT_BATTERY", "AIRBAG", "MASTER_WARNING",
            "ENGINE_OIL_PRESSURE", "ENGINE_TEMPERATURE", "EV_SYSTEM", "POWER_DOWN", "RBS",
            "ENGINE", "LOW_EV_BATTERY", "LOW_FUEL", "LOW_FUEL_FCEV", "HEV_SYSTEM",
            "FCEV_SYSTEM", "EREV_SYSTEM", "H2_SENSOR", "H2_LEAK", "SCR", "PARTICLE_FILTER",
        )
        private fun format(number: Double) = String.format(Locale.US, "%,.1f", number)

        private fun areaLabel(names: List<String>): String = names.joinToString(" / ") { name ->
            when (name) {
                "ROW_1_LEFT", "LEFT_FRONT" -> "앞쪽 왼쪽"
                "ROW_1_RIGHT", "RIGHT_FRONT" -> "앞쪽 오른쪽"
                "ROW_2_LEFT", "LEFT_REAR" -> "뒤쪽 왼쪽"
                "ROW_2_RIGHT", "RIGHT_REAR" -> "뒤쪽 오른쪽"
                "ROW_2_CENTER" -> "뒤쪽 가운데"
                "ROW_3_LEFT" -> "3열 왼쪽"
                "ROW_3_RIGHT" -> "3열 오른쪽"
                "HOOD" -> "보닛"
                "REAR" -> "트렁크"
                "ROOF_TOP_1" -> "선루프 1"
                "ROOF_TOP_2" -> "선루프 2"
                else -> name
            }
        }
    }
}
