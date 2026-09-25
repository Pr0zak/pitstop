package com.pitstop.ui.vehicle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.ActiveVehicle
import com.pitstop.data.VehicleDirectory
import com.pitstop.domain.Maintenance
import com.pitstop.domain.MaintenancePreset
import com.pitstop.domain.ReminderItem
import com.pitstop.domain.StaleService
import com.pitstop.http.ExpenseDto
import com.pitstop.http.PitstopApi
import com.pitstop.http.VehicleDto
import com.pitstop.log.LogBuffer
import com.pitstop.notif.VehicleAlerts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ServiceUi(
    val loading: Boolean = true,
    val error: String? = null,
    val vehicle: VehicleDto? = null,
    val reminders: List<ReminderItem> = emptyList(),
    /** expense_id → interval fraction used (0–1), null = unknown start. */
    val progress: Map<String, Double?> = emptyMap(),
    val scheduled: List<ExpenseDto> = emptyList(),
    val history: List<ExpenseDto> = emptyList(),
    val categories: Map<Int, String> = emptyMap(),
    val stale: StaleService? = null,
    val currentOdo: Double? = null,
    val distInMiles: Boolean = true,
    /** Row showing the inline "Mark done?" confirm. */
    val confirmingDoneId: String? = null,
    /** Row whose Mark-done POST is in flight. */
    val busyId: String? = null,
    /** Preset awaiting its inline confirm. */
    val pendingPreset: MaintenancePreset? = null,
    val presetBusy: Boolean = false,
) {
    /** Web `showPresets`: nothing overdue, upcoming or scheduled. */
    val showPresets: Boolean
        get() = vehicle != null && !loading && error == null && reminders.isEmpty() && scheduled.isEmpty()
}

/**
 * Car → Service. Reminders from /maintenance/reminders, the Scheduled list
 * and service history from /expenses, and the one-tap presets — the web
 * MaintenanceView's logic (see [Maintenance]) on the phone.
 *
 * Activity-scoped by its screen: "Mark done" and preset creation are
 * writes, and a tab ViewModel's scope dies when the pager swipes away.
 */
@HiltViewModel
class ServiceViewModel @Inject constructor(
    private val api: PitstopApi,
    private val activeVehicle: ActiveVehicle,
    private val directory: VehicleDirectory,
    private val alerts: VehicleAlerts,
    private val logs: LogBuffer,
) : ViewModel() {

    private val _ui = MutableStateFlow(ServiceUi())
    val ui: StateFlow<ServiceUi> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var loadJob: Job? = null
    private var lastLoadMs = 0L

    init {
        viewModelScope.launch {
            var last: String? = null
            activeVehicle.slug.collect { slug ->
                val switched = last != null && last != slug
                last = slug
                if (switched) _ui.value = ServiceUi()
                load(force = switched)
            }
        }
    }

    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastLoadMs > 60_000L) load(force = false)
    }

    fun load(force: Boolean = true) {
        loadJob?.cancel()
        val cc = if (force) "no-cache" else null
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val slug = activeVehicle.current()
            val vehicles = directory.vehicles.value.takeIf { v -> v.any { it.slug == slug } && !force }
                ?: runCatching { directory.refresh(cc) }.getOrElse {
                    _ui.update { s -> s.copy(loading = false, error = "Couldn't reach the server") }
                    return@launch
                }
            val v = vehicles.firstOrNull { it.slug == slug } ?: run {
                _ui.update { it.copy(loading = false, error = if (slug.isBlank()) "Pick a vehicle in Settings" else "Vehicle \"$slug\" not on server") }
                return@launch
            }
            val remD = async { runCatching { api.getReminders(v.id, cc) } }
            val expD = async { runCatching { api.getExpenses(v.id, cc) } }
            val catD = async { runCatching { api.getExpenseCategories() } }
            val rem = remD.await()
            val exp = expD.await()
            val cats = catD.await().getOrNull().orEmpty()
            val reminders = rem.getOrNull()?.let { Maintenance.normalize(it) }
            val expenses = exp.getOrNull()
            if (reminders == null || expenses == null) {
                logs.warn(
                    "service: load failed",
                    mapOf("err" to ((rem.exceptionOrNull() ?: exp.exceptionOrNull())?.message ?: "?")),
                )
                _ui.update { it.copy(loading = false, vehicle = v, error = "Couldn't load reminders") }
                return@launch
            }
            val today = LocalDate.now()
            val byId = expenses.associateBy { it.id }
            val miles = Maintenance.distInMiles(v)
            val odo = Maintenance.currentOdo(v)
            lastLoadMs = System.currentTimeMillis()
            _ui.update {
                it.copy(
                    loading = false,
                    error = null,
                    vehicle = v,
                    reminders = reminders,
                    progress = reminders.associate { r -> r.expenseId to Maintenance.progress(r, byId[r.expenseId], today) },
                    scheduled = Maintenance.scheduled(expenses, reminders),
                    history = Maintenance.serviceHistory(expenses, cats),
                    categories = cats.associate { c -> c.id to c.name.trim() },
                    stale = Maintenance.staleService(expenses, odo, miles, today),
                    currentOdo = odo,
                    distInMiles = miles,
                )
            }
            runCatching { alerts.onReminders(v, reminders) }
        }
    }

    fun askDone(id: String?) = _ui.update { it.copy(confirmingDoneId = id) }

    fun markDone(item: ReminderItem) {
        if (_ui.value.busyId != null) return
        _ui.update { it.copy(busyId = item.expenseId, confirmingDoneId = null) }
        viewModelScope.launch {
            runCatching { api.markReminderDone(item.expenseId) }
                .onSuccess {
                    logs.info("service: reminder done", mapOf("expense_id" to item.expenseId))
                    _messages.tryEmit("Marked “${item.title}” done")
                    _ui.update { it.copy(busyId = null) }
                    load(force = true)
                }
                .onFailure { e ->
                    logs.warn("service: mark done failed", mapOf("err" to (e.message ?: e::class.java.simpleName)))
                    _ui.update { it.copy(busyId = null) }
                    _messages.tryEmit("Couldn't mark it done — check the connection")
                }
        }
    }

    fun askPreset(p: MaintenancePreset?) = _ui.update { it.copy(pendingPreset = p) }

    fun confirmPreset() {
        val st = _ui.value
        val p = st.pendingPreset ?: return
        val v = st.vehicle ?: return
        if (st.presetBusy) return
        _ui.update { it.copy(presetBusy = true) }
        viewModelScope.launch {
            val body = Maintenance.presetRequest(p, v.id, st.currentOdo, st.distInMiles, LocalDate.now())
            runCatching { api.createExpense(body) }
                .onSuccess {
                    _messages.tryEmit("Reminder set: ${p.title}")
                    _ui.update { it.copy(presetBusy = false, pendingPreset = null) }
                    load(force = true)
                }
                .onFailure { e ->
                    logs.warn("service: preset failed", mapOf("err" to (e.message ?: e::class.java.simpleName)))
                    _ui.update { it.copy(presetBusy = false) }
                    _messages.tryEmit("Couldn't create the reminder")
                }
        }
    }
}
