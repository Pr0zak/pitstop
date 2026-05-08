package com.pitstop

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pitstop.ui.config.ConfigScreen
import com.pitstop.ui.fuel.FuelAddScreen
import com.pitstop.ui.live.LiveScreen
import com.pitstop.ui.status.StatusScreen
import com.pitstop.ui.theme.PitstopTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitstopTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PitstopRoot()
                }
            }
        }
    }
}

private object Routes {
    const val STATUS = "status"
    const val CONFIG = "config"
    const val LIVE = "live"
    const val FUEL = "fuel"
}

@Composable
private fun PitstopRoot() {
    val nav = rememberNavController()

    // Up-front permission request (notifications + bluetooth + location).
    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* No-op: surfaces are responsible for re-checking before use. */ }

    LaunchedEffect(Unit) {
        launcher.launch(perms)
    }

    NavHost(navController = nav, startDestination = Routes.STATUS) {
        composable(Routes.STATUS) {
            StatusScreen(
                onOpenConfig = { nav.navigate(Routes.CONFIG) },
                onOpenLive = { nav.navigate(Routes.LIVE) },
                onOpenFuel = { nav.navigate(Routes.FUEL) },
            )
        }
        composable(Routes.CONFIG) {
            ConfigScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.LIVE) {
            LiveScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.FUEL) {
            FuelAddScreen(onBack = { nav.popBackStack() })
        }
    }
}
