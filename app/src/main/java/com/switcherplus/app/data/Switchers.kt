package com.switcherplus.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import com.switcherplus.app.guard.GuardAction
import com.switcherplus.app.guard.GuardLogic
import com.switcherplus.app.protocol.SwitcherClient
import com.switcherplus.app.protocol.SwitcherListener
import com.switcherplus.app.protocol.SwitcherProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceConfig(
    val id: String,
    val key: String,
    val name: String,
    val ip: String,
    val modelName: String,
    val guardEnabled: Boolean = true,
    val limitMinutes: Int = 15,
)

data class DeviceStatus(
    val on: Boolean = false,
    val powerWatts: Int = 0,
    val timeLeftSec: Long = 0,
    val timeOnSec: Long = 0,
    val updatedAtMs: Long = 0,
    val reachable: Boolean = false,
    val noLimitThisRun: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

data class Device(val config: DeviceConfig, val status: DeviceStatus)

/** Process-wide store + engine. Shared by the UI and the background guard service. */
@SuppressLint("StaticFieldLeak")
object Switchers {
    private const val PREFS = "switchers"
    private const val KEY_DEVICES = "devices"
    private const val KEY_LOG = "log"
    private const val MAX_LOG = 40

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun lock(id: String): Mutex = locks.getOrPut(id) { Mutex() }
    private val lastTimerLogAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val configs = MutableStateFlow<Map<String, DeviceConfig>>(emptyMap())
    private val statuses = MutableStateFlow<Map<String, DeviceStatus>>(emptyMap())
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val users = mutableSetOf<String>()
    private var engineJob: Job? = null
    private var listener: SwitcherListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var fastPolling = false

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        loadPrefs()
    }

    val anyGuardEnabled: Boolean get() = configs.value.values.any { it.guardEnabled }

    // ---------------- lifecycle ----------------

    /** [user] = "ui" or "service". Engine runs while at least one user holds it. */
    @Synchronized
    fun acquire(user: String) {
        users.add(user)
        fastPolling = "ui" in users
        if (engineJob?.isActive == true) return
        startEngine()
    }

    @Synchronized
    fun release(user: String) {
        users.remove(user)
        fastPolling = "ui" in users
        if (users.isEmpty()) stopEngine()
    }

    private fun startEngine() {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("switcherplus").apply {
            setReferenceCounted(false); acquire()
        }
        val l = SwitcherListener { bc, ip -> scope.launch { onBroadcast(bc, ip) } }
        listener = l
        _scanning.value = true
        engineJob = scope.launch {
            launch { l.run() }
            launch {
                delay(15_000)
                _scanning.value = false
            }
            while (isActive) {
                for (cfg in configs.value.values) {
                    if (!lock(cfg.id).isLocked) launch { pollAndGuard(cfg.id) }
                }
                delay(if (fastPolling) 5_000 else 20_000)
            }
        }
    }

    private fun stopEngine() {
        listener?.close(); listener = null
        engineJob?.cancel(); engineJob = null
        runCatching { multicastLock?.release() }; multicastLock = null
        _scanning.value = false
    }

    fun rescan() {
        _scanning.value = true
        scope.launch { delay(15_000); _scanning.value = false }
    }

    // ---------------- engine ----------------

    private suspend fun onBroadcast(bc: SwitcherProtocol.Broadcast, ip: String) {
        val existing = configs.value[bc.deviceId]
        if (existing == null) {
            val name = bc.name.ifBlank { bc.modelName }
            putConfig(DeviceConfig(bc.deviceId, bc.deviceKey, name, ip, bc.modelName))
            addLog("נמצא מכשיר חדש: $name")
            pollAndGuard(bc.deviceId)
        } else if (existing.ip != ip || existing.key != bc.deviceKey) {
            putConfig(existing.copy(ip = ip, key = bc.deviceKey))
        }
        // Broadcast is a cheap way to notice state changes fast (e.g. physical button).
        val st = statuses.value[bc.deviceId]
        if (st == null || st.on != bc.on) pollAndGuard(bc.deviceId)
    }

    private fun client(cfg: DeviceConfig) = SwitcherClient(cfg.ip, cfg.id, cfg.key)

    private suspend fun pollAndGuard(id: String) {
        val cfg = configs.value[id] ?: return
        lock(id).withLock {
            val state = try {
                client(cfg).getState()
            } catch (e: Exception) {
                updateStatus(id) { it.copy(reachable = false, error = "לא מגיב ברשת") }
                return
            }
            updateStatus(id) {
                it.copy(
                    on = state.on, powerWatts = if (state.on) state.powerWatts else 0,
                    timeLeftSec = if (state.on) state.timeLeftSec else 0,
                    timeOnSec = if (state.on) state.timeOnSec else 0,
                    updatedAtMs = System.currentTimeMillis(), reachable = true, error = null,
                    noLimitThisRun = if (state.on) it.noLimitThisRun else false,
                )
            }
            val st = statuses.value[id] ?: return
            if (!cfg.guardEnabled || st.noLimitThisRun) return
            when (val action = GuardLogic.decide(state.on, state.timeOnSec, state.timeLeftSec, cfg.limitMinutes)) {
                is GuardAction.None -> Unit
                is GuardAction.TurnOff -> runCatching {
                    client(cfg).turnOff()
                    addLog("${cfg.name}: כובה — עבר הזמן המקסימלי (${cfg.limitMinutes} דק')")
                    updateStatus(id) { it.copy(on = false, powerWatts = 0, timeLeftSec = 0, timeOnSec = 0) }
                }
                is GuardAction.SetTimer -> runCatching {
                    client(cfg).turnOn(action.minutes)
                    val now = System.currentTimeMillis()
                    if (now - (lastTimerLogAt[id] ?: 0L) > 120_000) {
                        addLog("${cfg.name}: זוהה דלוק — ייכבה בעוד ${action.minutes} דק'")
                    }
                    lastTimerLogAt[id] = now
                    updateStatus(id) { it.copy(timeLeftSec = action.minutes * 60L) }
                }
            }
        }
    }

    // ---------------- user actions ----------------

    private fun act(id: String, label: String, block: suspend (DeviceConfig) -> Unit) {
        val cfg = configs.value[id] ?: return
        scope.launch {
            updateStatus(id) { it.copy(busy = true, error = null) }
            try {
                lock(id).withLock { block(cfg) }
                addLog("${cfg.name}: $label")
            } catch (e: Exception) {
                updateStatus(id) { it.copy(error = "הפעולה נכשלה — האם הטלפון על הרשת הביתית?") }
            } finally {
                updateStatus(id) { it.copy(busy = false) }
            }
            pollAndGuard(id)
        }
    }

    /** Turn on, respecting the configured limit (device-side timer). */
    fun turnOn(id: String) {
        val cfg = configs.value[id] ?: return
        val minutes = if (cfg.guardEnabled) cfg.limitMinutes else 0
        act(id, if (minutes > 0) "הודלק ל-$minutes דק'" else "הודלק") {
            client(it).turnOn(minutes)
            updateStatus(id) { s -> s.copy(noLimitThisRun = false) }
        }
    }

    fun turnOnFor(id: String, minutes: Int) = act(id, "הודלק ל-$minutes דק'") {
        client(it).turnOn(minutes)
        // A manual timer longer than the limit counts as a deliberate override.
        val override = it.guardEnabled && minutes > it.limitMinutes
        updateStatus(id) { s -> s.copy(noLimitThisRun = override) }
    }

    fun turnOnNoLimit(id: String) = act(id, "הודלק ללא הגבלת זמן (עד הכיבוי הבא)") {
        updateStatus(id) { s -> s.copy(noLimitThisRun = true) }
        client(it).turnOn(0)
    }

    fun turnOff(id: String) = act(id, "כובה") { client(it).turnOff() }

    fun setGuard(id: String, enabled: Boolean) {
        val cfg = configs.value[id] ?: return
        putConfig(cfg.copy(guardEnabled = enabled))
        addLog("${cfg.name}: כיבוי אוטומטי ${if (enabled) "הופעל (${cfg.limitMinutes} דק')" else "בוטל"}")
        if (enabled) scope.launch { pollAndGuard(id) }
    }

    fun setLimit(id: String, minutes: Int) {
        val cfg = configs.value[id] ?: return
        putConfig(cfg.copy(limitMinutes = minutes.coerceIn(1, 600)))
    }

    /** Apply a new limit right away (called when the user finishes editing). */
    fun applyLimitNow(id: String) {
        scope.launch {
            val cfg = configs.value[id] ?: return@launch
            // Drop any countdown longer than the new limit by re-evaluating from scratch.
            pollAndGuard(cfg.id)
        }
    }

    fun rename(id: String, name: String) {
        val cfg = configs.value[id] ?: return
        if (name.isNotBlank()) putConfig(cfg.copy(name = name.trim()))
    }

    fun forget(id: String) {
        configs.update { it - id }
        statuses.update { it - id }
        savePrefs(); publish()
    }

    // ---------------- widget support ----------------

    fun device(id: String?): Device? {
        val list = _devices.value
        return list.firstOrNull { it.config.id == id } ?: list.firstOrNull()
    }

    /** Refresh one device's state now (used by the widget when the engine isn't running). */
    suspend fun refresh(id: String) = pollAndGuard(id)

    /** Toggle based on the device's real current state. Turning on respects the auto-off limit. */
    suspend fun toggleNow(id: String) {
        val cfg = configs.value[id] ?: return
        updateStatus(id) { it.copy(busy = true, error = null) }
        try {
            lock(id).withLock {
                val st = client(cfg).getState()
                if (st.on) {
                    client(cfg).turnOff()
                    addLog("${cfg.name}: כובה מהווידג'ט")
                    updateStatus(id) { it.copy(on = false, powerWatts = 0, timeLeftSec = 0, timeOnSec = 0) }
                } else {
                    val minutes = if (cfg.guardEnabled) cfg.limitMinutes else 0
                    client(cfg).turnOn(minutes)
                    addLog("${cfg.name}: הודלק מהווידג'ט" + if (minutes > 0) " ל-$minutes דק'" else "")
                    updateStatus(id) {
                        it.copy(
                            on = true, noLimitThisRun = false, timeOnSec = 0, timeLeftSec = minutes * 60L,
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            updateStatus(id) { it.copy(reachable = false, error = "לא מגיב ברשת") }
        } finally {
            updateStatus(id) { it.copy(busy = false) }
        }
        pollAndGuard(id)
    }

    fun clearLog() {
        _log.value = emptyList(); saveLog()
    }

    // ---------------- state plumbing ----------------

    private fun updateStatus(id: String, f: (DeviceStatus) -> DeviceStatus) {
        statuses.update { m -> m + (id to f(m[id] ?: DeviceStatus())) }
        publish()
    }

    private fun putConfig(cfg: DeviceConfig) {
        configs.update { it + (cfg.id to cfg) }
        savePrefs(); publish()
    }

    private fun publish() {
        val s = statuses.value
        _devices.value = configs.value.values
            .sortedBy { it.name }
            .map { Device(it, s[it.id] ?: DeviceStatus()) }
        if (::appContext.isInitialized) {
            runCatching { com.switcherplus.app.widget.PowerWidget.onDevicesChanged(appContext, _devices.value) }
        }
    }

    private fun addLog(line: String) {
        val time = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        _log.update { (listOf("$time  $line") + it).take(MAX_LOG) }
        saveLog()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadPrefs() {
        val arr = runCatching { JSONArray(prefs().getString(KEY_DEVICES, "[]")) }.getOrNull() ?: JSONArray()
        val map = mutableMapOf<String, DeviceConfig>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val c = DeviceConfig(
                id = o.getString("id"), key = o.getString("key"), name = o.getString("name"),
                ip = o.getString("ip"), modelName = o.optString("model", "Switcher"),
                guardEnabled = o.optBoolean("guard", true), limitMinutes = o.optInt("limit", 15),
            )
            map[c.id] = c
        }
        configs.value = map
        val logArr = runCatching { JSONArray(prefs().getString(KEY_LOG, "[]")) }.getOrNull() ?: JSONArray()
        _log.value = (0 until logArr.length()).map { logArr.getString(it) }
        publish()
    }

    private fun savePrefs() {
        val arr = JSONArray()
        configs.value.values.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("key", it.key).put("name", it.name).put("ip", it.ip)
                    .put("model", it.modelName).put("guard", it.guardEnabled).put("limit", it.limitMinutes)
            )
        }
        prefs().edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    private fun saveLog() {
        prefs().edit().putString(KEY_LOG, JSONArray(_log.value).toString()).apply()
    }
}
