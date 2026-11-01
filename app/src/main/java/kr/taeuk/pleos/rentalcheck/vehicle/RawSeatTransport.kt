package kr.taeuk.pleos.rentalcheck.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import ai.pleos.playground.vehicle.IVehicleService
import ai.pleos.playground.vehicle.callback.IGetPropertyResult
import ai.pleos.playground.vehicle.callback.IPropertyCallback
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Read-only compatibility bridge for Vehicle 2.3.2 / vehicle-aidl 1.1.1, before its lossy Boolean conversion. */
internal class RawSeatTransport(private val context: Context) : Closeable {
    @Volatile private var service: IVehicleService? = null
    @Volatile private var problem: Exception? = null
    private var bound = false
    private val active = AtomicBoolean(false)
    private data class Observer(val changed: (String?) -> Unit, val failed: (Exception) -> Unit, val active: AtomicBoolean = AtomicBoolean(true))
    private val listeners = ConcurrentHashMap<Int, Observer>()
    private var subscribed = false
    private val lock = Any()
    @Volatile private var callback: IPropertyCallback? = null

    // Give every subscription generation its own binder so queued events from a stopped or disconnected generation are rejected.
    private fun newCallback(): IPropertyCallback = object : IPropertyCallback.Stub() {
        // The service subscribes by value type, not seat area; dispatch its lossless area-tagged events locally.
        override fun onChangeEvent(property: String?, area: Int, value: String?) {
            val observer = listeners[area]
            if (active.get() && callback === this && property == PROPERTY && observer?.active?.get() == true) observer.changed(value)
        }
        // A property-level failure invalidates every subscribed seat without creating a false vacant signal.
        override fun onErrorEvent(property: String?, error: String?) {
            val observers = listeners.values.toList()
            if (active.get() && callback === this && property == PROPERTY) observers.filter { it.active.get() }
                .forEach { it.failed(IllegalStateException(error ?: "좌석 변경 수신 실패")) }
        }
    }
    private val connection = object : ServiceConnection {
        // Reconnect the same permission-checked service and restore only still-active seat subscriptions.
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!active.get()) return
            synchronized(lock) {
                service = IVehicleService.Stub.asInterface(binder); problem = null; subscribed = false; callback = null
                if (listeners.isNotEmpty()) runCatching { subscribe() }.onFailure { notifyFailure(it.message.orEmpty()) }
            }
        }
        // A disconnected service invalidates live state; old good values must not remain as apparently current empty seats.
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { service = null; subscribed = false; callback = null }
            notifyFailure("Vehicle Service 연결이 끊겼습니다.")
        }
        // A rejected binding remains an explicit error instead of triggering a fallback that inverts an ambiguous Boolean.
        override fun onNullBinding(name: ComponentName?) { problem = IllegalStateException("Vehicle Service가 바인딩을 거부했습니다."); onServiceDisconnected(name) }
    }

    // Invalidate presentation after a connection failure even though the retired callback binder is already disabled.
    private fun notifyFailure(message: String) {
        if (active.get()) listeners.values.filter { it.active.get() }.forEach { it.failed(IllegalStateException(message)) }
    }

    // Use the pinned SDK's INTEGER type ID 3 once per property; treating this argument as an area can crash the vendor service.
    private fun subscribe() {
        if (!subscribed) {
            val next = newCallback(); callback = next
            try { checkNotNull(service).registerPropertyCallback(context.packageName, PROPERTY, 3, next); subscribed = true }
            catch (error: Exception) { callback = null; throw error }
        }
    }

    // Bind exactly the service/component used by the pinned SDK; no extra permission, vehicle control or reflection is involved.
    fun initialize() {
        if (!active.compareAndSet(false, true)) return
        try {
            bound = context.bindService(Intent("ai.pleos.playground.vehicle.VEHICLE_START").setComponent(ComponentName(
                "ai.pleos.playground.service.vehicle", "ai.pleos.playground.service.vehicle.VehicleService")), connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Vehicle Service에 연결할 수 없습니다." }
        } catch (error: Exception) { problem = error }
    }

    // Bound connection startup separately from each getter so missing services and late binder replies cannot manufacture a value.
    private suspend fun connected(): IVehicleService = withTimeout(4_000) {
        while (true) {
            check(active.get()) { "좌석 읽기 연결이 종료됐습니다." }
            problem?.let { throw it }
            service?.let { return@withTimeout it }
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    // Fetch the original integer string using the SDK's property/type/area contract, checking the reply's property and area.
    suspend fun read(area: Int): String? {
        val api = connected()
        return sdkAwait { ok, fail -> api.getPropertyValue(PROPERTY, 3, area, object : IGetPropertyResult.Stub() {
            // Keep raw 0, 1, 2 and malformed/null payloads intact until the conservative model decoder handles them.
            override fun onResult(property: String?, replyArea: Int, value: String?) {
                if (property == PROPERTY && replyArea == area) ok(value) else fail(IllegalStateException("좌석 응답 영역이 일치하지 않습니다."))
            }
            // Preserve service-side permission and availability failures as errors rather than as vacant or occupied.
            override fun onFailed(property: String?, replyArea: Int, error: String?) { fail(IllegalStateException(error ?: "좌석 읽기 실패")) }
        }) }
    }

    // Register one raw callback per supported seat and reject queued events after the owning foreground subscription closes.
    suspend fun observe(area: Int, changed: (String?) -> Unit, failed: (Exception) -> Unit): Closeable {
        connected()
        val observer = Observer(changed, failed)
        synchronized(lock) {
            check(listeners.putIfAbsent(area, observer) == null) { "이미 구독 중인 좌석입니다." }
            try { subscribe() } catch (error: Exception) { listeners.remove(area, observer); observer.active.set(false); throw error }
        }
        return Closeable {
            if (observer.active.compareAndSet(true, false)) synchronized(lock) {
                listeners.remove(area, observer)
                if (listeners.isEmpty() && subscribed) {
                    val retired = callback; callback = null
                    if (retired != null) runCatching { service?.unregisterPropertyCallback(context.packageName, PROPERTY, 3, retired) }
                    subscribed = false
                }
            }
        }
    }

    // Unregister callbacks before unbinding, rejecting all late service connections and callbacks from this lifetime.
    override fun close() {
        if (!active.compareAndSet(true, false)) return
        synchronized(lock) {
            listeners.values.forEach { it.active.set(false) }
            val retired = callback; callback = null
            if (subscribed && retired != null) runCatching { service?.unregisterPropertyCallback(context.packageName, PROPERTY, 3, retired) }
            subscribed = false; listeners.clear(); service = null; problem = null
        }
        if (bound) { context.unbindService(connection); bound = false }
    }

    companion object { private const val PROPERTY = "SEAT_OCCUPANCY" }
}
