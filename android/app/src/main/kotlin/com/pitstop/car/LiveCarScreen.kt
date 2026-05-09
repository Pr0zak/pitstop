package com.pitstop.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.pitstop.R
import com.pitstop.service.BridgeStateBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The first (and currently only) head-unit screen. Renders four large tiles
 * — speed, RPM, coolant temp, fuel level — backed by the same
 * [BridgeStateBus] the phone Live view uses. As the bridge service publishes
 * new metric samples we [invalidate]; the framework calls [onGetTemplate]
 * again and the user sees the fresh number.
 *
 * We intentionally don't include GPS or IMU on this screen — the head-unit
 * already shows those, and Android Auto's template constraints punish info
 * overload. Speed-from-OBD is included anyway because it's the value the
 * pitstop bridge actually published; it's a useful sanity check while
 * driving that the bridge is alive.
 */
class LiveCarScreen(
    carContext: CarContext,
    private val stateBus: BridgeStateBus,
) : Screen(carContext), DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observerJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        observerJob = scope.launch {
            // Coalesce. Sensors emit much faster than the head unit can
            // re-render — collectLatest keeps the latest snapshot only.
            stateBus.latestByMetric.collectLatest { invalidate() }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        observerJob?.cancel()
        observerJob = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        scope.cancel()
    }

    override fun onGetTemplate(): Template {
        val metrics = stateBus.latestByMetric.value
        val status = stateBus.status.value

        val items = listOf(
            tile("Speed", formatNumber(metrics["vehicle_speed"]?.value, "kph"), iconRes()),
            tile("RPM", formatNumber(metrics["engine_rpm"]?.value, "")),
            tile("Coolant", formatNumber(metrics["coolant_temp"]?.value, "°C")),
            tile("Fuel", formatNumber(metrics["fuel_level"]?.value, "%")),
        )

        return GridTemplate.Builder()
            .setTitle("Pitstop")
            .setSingleList(ItemList.Builder().apply { items.forEach { addItem(it) } }.build())
            .setHeaderAction(androidx.car.app.model.Action.APP_ICON)
            .apply {
                if (!status.brokerConnected) {
                    setActionStrip(
                        androidx.car.app.model.ActionStrip.Builder()
                            .addAction(
                                androidx.car.app.model.Action.Builder()
                                    .setTitle("Broker offline")
                                    .build(),
                            )
                            .build(),
                    )
                }
            }
            .build()
    }

    private fun tile(label: String, value: String, icon: CarIcon? = null): GridItem {
        val builder = GridItem.Builder()
            .setTitle(value)
            .setText(label)
        if (icon != null) builder.setImage(icon, GridItem.IMAGE_TYPE_ICON)
        return builder.build()
    }

    private fun iconRes(): CarIcon =
        CarIcon.Builder(
            androidx.core.graphics.drawable.IconCompat.createWithResource(
                carContext,
                R.drawable.ic_launcher_foreground,
            ),
        )
            .setTint(CarColor.PRIMARY)
            .build()

    private fun formatNumber(v: Double?, unit: String): String {
        if (v == null) return "—"
        val formatted = if (kotlin.math.abs(v - v.toLong()) < 1e-3) v.toLong().toString()
        else "%.1f".format(v)
        return if (unit.isBlank()) formatted else "$formatted $unit"
    }
}
