package app.throttlevpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import androidx.core.graphics.drawable.toBitmap
import kotlin.concurrent.thread
import kotlin.math.roundToInt

data class AppInfo(val pkg: String, val label: String)

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        Access.foreground = true
        Billing.refresh()
    }

    override fun onPause() {
        Access.foreground = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LimitStore.init(this)
        Access.init(this)
        Billing.init(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

private fun vpnIntent(ctx: Context, action: String) =
    Intent(ctx, ThrottleVpnService::class.java).setAction(action)

private fun restartIfRunning(ctx: Context) {
    if (ThrottleVpnService.running) ctx.startService(vpnIntent(ctx, ThrottleVpnService.ACTION_START))
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    val running = ThrottleVpnService.running
    val selected = LimitStore.selected
    val activity = ctx as android.app.Activity
    var showDialog by remember { mutableStateOf(true) }
    val allowed = Access.allowed()
    val intro = !Access.trialStarted

    if (showDialog && !Access.premium && (intro || !allowed)) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(if (intro) "Try it for 5 minutes" else "Trial ended") },
            text = {
                Text(
                    if (intro) "You can test Throttle VPN for 5 minutes. After that, a one-time purchase of ${Billing.price} unlocks it permanently."
                    else "Your 5-minute trial is over. Unlock the full version for ${Billing.price} (one-time purchase)."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (intro) { Access.startTrial(); showDialog = false }
                    else { showDialog = false; Billing.buy(activity) }
                }) { Text(if (intro) "Start trial" else "Unlock ${Billing.price}") }
            },
            dismissButton = if (!intro && !allowed) {
                { TextButton(onClick = { showDialog = false }) { Text("Not now") } }
            } else null
        )
    }

    LaunchedEffect(Unit) {
        thread {
            val pm = ctx.packageManager
            apps = pm.getInstalledApplications(0)
                .filter { it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { AppInfo(it.packageName, it.loadLabel(pm).toString()) }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) {
            ctx.startService(vpnIntent(ctx, ThrottleVpnService.ACTION_START))
        }
    }

    val shown = apps
        .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
        .sortedWith(compareByDescending<AppInfo> { it.pkg in selected }.thenBy { it.label.lowercase() })

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Throttle VPN", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (running) "VPN running \u2013 limits active" else "VPN is off",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (Access.premium || (Access.trialStarted && !allowed)) Text(
                    if (Access.premium) "Full version unlocked" else "Trial ended",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        if (running) {
                            ctx.startService(vpnIntent(ctx, ThrottleVpnService.ACTION_STOP))
                        } else if (!allowed) {
                            if (!Access.trialStarted) showDialog = true else Billing.buy(activity)
                        } else {
                            val i = VpnService.prepare(ctx)
                            if (i != null) launcher.launch(i)
                            else ctx.startService(vpnIntent(ctx, ThrottleVpnService.ACTION_START))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (running) "Stop" else if (allowed) "Start" else "Unlock ${Billing.price}") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Whole device", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Limits the traffic of all apps",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = LimitStore.globalEnabled,
                            onCheckedChange = {
                                LimitStore.enableGlobal(it)
                                restartIfRunning(ctx)
                            }
                        )
                    }
                    if (LimitStore.globalEnabled) {
                        SpeedSlider("Upload", LimitStore.global.upIdx) { LimitStore.setGlobalUp(it) }
                        SpeedSlider("Download", LimitStore.global.downIdx) { LimitStore.setGlobalDown(it) }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        items(shown, key = { it.pkg }) { app ->
            val sel = app.pkg in selected
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = remember(app.pkg) {
                            runCatching {
                                ctx.packageManager.getApplicationIcon(app.pkg).toBitmap(96, 96).asImageBitmap()
                            }.getOrNull()
                        }
                        if (icon != null) Image(icon, null, Modifier.size(40.dp))
                        else Spacer(Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleSmall)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = sel, onCheckedChange = {
                            LimitStore.select(app.pkg, it)
                            restartIfRunning(ctx)
                        })
                    }
                    if (sel) {
                        val l = LimitStore.limits(app.pkg)
                        SpeedSlider("Upload", l.upIdx) { LimitStore.setUp(app.pkg, it) }
                        SpeedSlider("Download", l.downIdx) { LimitStore.setDown(app.pkg, it) }
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedSlider(title: String, initial: Int, onChange: (Int) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Column {
        Text("$title: ${LimitStore.label(v)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = v.toFloat(),
            onValueChange = {
                val n = it.roundToInt()
                if (n != v) {
                    v = n
                    onChange(n)
                }
            },
            valueRange = 0f..1000f
        )
    }
}
