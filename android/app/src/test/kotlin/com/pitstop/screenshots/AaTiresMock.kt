package com.pitstop.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.pitstop.car.TirePane
import com.pitstop.data.CachedReading
import com.pitstop.widget.TireWidgetProvider
import org.junit.Rule
import org.junit.Test

/**
 * A MOCK of the Android Auto Tires tab at the Pilot's 800×480 @160 dpi:
 * the AA chrome (tab strip, bottom app bar) is drawn by hand to look like
 * the host, but the pane's image and row text come from the real code
 * (TireWidgetRender.Size.Car + TirePane.rows). The DHU is the real check.
 */
class AaTiresMock {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 800,
            screenHeight = 480,
            xdpi = 160,
            ydpi = 160,
            density = Density.MEDIUM,
            orientation = ScreenOrientation.LANDSCAPE,
            softButtons = false,
        ),
        useDeviceResolution = true,
    )

    private val now = 1_800_000_000_000L

    private fun snap(fr: Double, ageMs: Long, temps: List<Double>) = buildMap {
        put("tire_pressure_fl", CachedReading(244.0, now - ageMs))
        put("tire_pressure_fr", CachedReading(fr, now - ageMs))
        put("tire_pressure_rl", CachedReading(246.0, now - ageMs))
        put("tire_pressure_rr", CachedReading(245.0, now - ageMs))
        listOf("fl", "fr", "rl", "rr").forEachIndexed { i, c ->
            put("tire_temp_$c", CachedReading(temps[i], now - ageMs))
        }
        put(TireWidgetProvider.PLACARD_FRONT, CachedReading(241.3, now))
        put(TireWidgetProvider.PLACARD_REAR, CachedReading(241.3, now))
    }

    @Test fun driving() = frame(snap(fr = 245.0, ageMs = 20_000L, temps = listOf(24.0, 25.0, 23.0, 24.0)))

    @Test fun drivingLow() = frame(snap(fr = 214.0, ageMs = 20_000L, temps = listOf(24.0, 22.0, 23.0, 24.0)))

    @Test fun parked() = frame(snap(fr = 236.0, ageMs = 5 * 3_600_000L, temps = listOf(13.0, 12.0, 13.0, 13.0)))

    /** The car image enlarged, and at the head unit's real ~190 px. */
    @Test fun carImageSizes() = paparazzi.snapshot {
        val m = TireWidgetProvider.modelOf(snap(fr = 214.0, ageMs = 20_000L, temps = listOf(24.0, 22.0, 23.0, 24.0)), now, "imperial")
        val bmp = com.pitstop.car.TireCarBitmap.render(m)
        Row(
            Modifier.fillMaxSize().background(Color(0xFF202124)).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(bmp.asImageBitmap(), null, Modifier.size(420.dp))
            Image(bmp.asImageBitmap(), null, Modifier.size(190.dp))
        }
    }

    /** Cylinders tab image: all six, VCM eco with a cyl-6 misfire, engine off. */
    @Test fun cylinders() = paparazzi.snapshot {
        val six = com.pitstop.car.CylinderModel.of(6.0, now, emptyMap(), now, null)
        val eco = com.pitstop.car.CylinderModel.of(3.0, now, mapOf(6 to 1), now, null)
        val off = com.pitstop.car.CylinderModel.of(null, null, emptyMap(), now, "Engine off")
        Row(
            Modifier.fillMaxSize().background(Color(0xFF202124)).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (m in listOf(six, eco, off)) {
                Image(com.pitstop.car.CylinderBitmap.render(m, if (m.eco) 42 else null).asImageBitmap(), null, Modifier.size(240.dp))
            }
        }
    }

    /** Temps tab image: normal drive, coolant hot, cold morning. */
    @Test fun temps() = paparazzi.snapshot {
        fun live(vararg kv: Pair<String, Double>) =
            kv.associate { (k, v) -> k to com.pitstop.service.MetricSample(k, v, now - 2_000L) }
        val normal = com.pitstop.car.TempModel.of(
            live("coolant_temp" to 90.0, "atf_temp" to 72.0, "intake_air_temp" to 24.0, "ambient_air_temp_f" to 68.0), now, "imperial", null,
        )
        val hot = com.pitstop.car.TempModel.of(
            live("coolant_temp" to 108.0, "atf_temp" to 112.0, "intake_air_temp" to 41.0, "ambient_air_temp_f" to 98.0), now, "imperial", null,
        )
        val cold = com.pitstop.car.TempModel.of(
            live("coolant_temp" to 35.0, "atf_temp" to 12.0, "intake_air_temp" to 1.0, "ambient_air_temp_f" to 33.0), now, "imperial", null,
        )
        Row(
            Modifier.fillMaxSize().background(Color(0xFF202124)).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (m in listOf(normal, hot, cold)) {
                Image(com.pitstop.car.TempBitmap.render(m).asImageBitmap(), null, Modifier.size(240.dp))
            }
        }
    }

    private fun frame(s: Map<String, CachedReading>) = paparazzi.snapshot {
        val model = TireWidgetProvider.modelOf(s, now, "imperial")
        val rows = TirePane.rows(model, "imperial")
        val bmp = com.pitstop.car.TireCarBitmap.render(model)
        AaFrame(selectedTab = 3) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Top) {
                    rows.forEachIndexed { i, (title, value) ->
                        Column(Modifier.padding(vertical = 9.dp)) {
                            Text(title, color = Color.White, fontSize = 18.sp)
                            Text(value, color = Color(0xFFBDC1C6), fontSize = 15.sp)
                        }
                        if (i < rows.lastIndex) HorizontalDivider(color = Color(0xFF3C4043))
                    }
                }
                Spacer(Modifier.width(20.dp))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Image(bmp.asImageBitmap(), null, Modifier.size(190.dp))
                }
            }
        }
    }
}

/** Hand-drawn stand-in for the AA host chrome at 800×480. */
@Composable
private fun AaFrame(selectedTab: Int, content: @Composable () -> Unit) {
    val tabs = listOf("Drive", "Engine", "Fuel", "Tires")
    Column(Modifier.fillMaxSize().background(Color(0xFF000000))) {
        // Header: app icon + tab strip.
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).background(Color(0xFF8AB4F8), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("P", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            Spacer(Modifier.width(20.dp))
            tabs.forEachIndexed { i, t ->
                val on = i == selectedTab
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .background(if (on) Color(0xFF3C4043) else Color.Transparent, RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(t, color = if (on) Color.White else Color(0xFF9AA0A6), fontSize = 16.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF202124))) { content() }
        // AA app bar (bottom at this size): launcher, clock, mic.
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF000000)).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(22.dp).background(Color(0xFF5F6368), RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(18.dp))
            Text("2:41", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(28.dp).background(Color(0xFF3C4043), CircleShape))
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(28.dp).background(Color(0xFF3C4043), CircleShape))
        }
    }
}
