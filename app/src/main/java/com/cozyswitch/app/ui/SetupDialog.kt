package com.cozyswitch.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.cozyswitch.app.data.FoundPlug
import com.cozyswitch.app.data.PlugSetup
import com.cozyswitch.app.data.Plugs
import com.cozyswitch.app.data.SavedWifi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class Stage { Intro, Searching, Pick, Wifi, Working, Done, Failed }

/** Puts a plug in setup mode on Wi-Fi over Bluetooth, like "Add device" in the CozyLife app. */
@Composable
fun SetupDialog(waiting: List<FoundPlug> = emptyList(), onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup = remember { PlugSetup(ctx) }
    val saved = remember { SavedWifi.get(ctx) }
    // Opened from the "plug to add" card: skip the search, like CozyLife's Add button.
    var stage by remember { mutableStateOf(when { waiting.size == 1 -> Stage.Wifi; waiting.isNotEmpty() -> Stage.Pick; else -> Stage.Intro }) }
    val found = remember { mutableStateListOf<FoundPlug>().apply { addAll(waiting) } }
    var chosen by remember { mutableStateOf(waiting.singleOrNull()) }
    var ssid by remember { mutableStateOf(saved?.first ?: currentSsid(ctx)) }
    var pw by remember { mutableStateOf(saved?.second ?: "") }
    var keepWifi by remember { mutableStateOf(true) }
    var showPw by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val session = remember { Plugs.session(ctx) }
    val known = remember { Plugs.load(ctx).associate { it.did to it.name } }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }


    fun search() {
        found.clear(); stage = Stage.Searching
        job = scope.launch {
            try {
                setup.scan(12_000) { list -> scope.launch { found.clear(); found.addAll(list) } }
                stage = Stage.Pick
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = e.message; stage = Stage.Failed }
        }
    }

    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (setup.bluetoothOn()) search()
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
            if (setup.bluetoothOn()) search() else enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            error = "Cozy Switch needs the Nearby devices permission to find the plug over Bluetooth."
            stage = Stage.Failed
        }
    }

    fun start() {
        val need = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (need.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) {
            if (setup.bluetoothOn()) search() else enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else perms.launch(need)
    }

    fun go() {
        val p = chosen ?: return
        val s = session ?: return
        log.clear(); stage = Stage.Working
        job = scope.launch {
            try {
                setup.setUp(p, ssid.trim(), pw, s) { line -> scope.launch { log += line } }
                if (keepWifi) SavedWifi.put(ctx, ssid.trim(), pw) else SavedWifi.forget(ctx)
                stage = Stage.Done
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                error = e.message ?: "Setup failed."
                stage = Stage.Failed
            }
        }
    }

    // Same Wi-Fi as last time and one plug waiting: go straight to setting it up.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (stage == Stage.Wifi && saved != null && session != null) go()
    }

    Dialog(onDismissRequest = { if (stage != Stage.Working) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 720.dp).clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF16212C)).verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Text("Set up a plug", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            val dim = Color.White.copy(alpha = 0.65f)
            when (stage) {
                Stage.Intro -> {
                    if (session == null) {
                        Text("Sign in to CozyLife first (the \"Away from home\" card on the Plugs tab). The plug " +
                            "registers itself to your account during setup, so it needs your sign-in.\n\n" +
                            "If you're already signed in, sign out and in again once.", color = Color.White, fontSize = 14.sp)
                        TextButton(onClick = onClose) { Text("OK") }
                    } else {
                        Text("Use this for a new plug, or one that has been reset or lost its Wi-Fi.", color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("1. Put the plug in setup mode: plug it in, then (for most CozyLife plugs) unplug and plug " +
                            "it back in 3 times, or hold its button for 5 seconds, until the light blinks quickly.\n" +
                            "2. Keep the phone close to the plug.\n" +
                            "3. Your Wi-Fi must be 2.4 GHz. The plugs can't use 5 GHz.", color = dim, fontSize = 13.sp)
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { start() }, modifier = Modifier.fillMaxWidth()) { Text("Look for plugs") }
                        TextButton(onClick = onClose) { Text("Cancel") }
                    }
                }
                Stage.Searching, Stage.Pick -> {
                    if (stage == Stage.Searching) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking for plugs in setup mode…", color = Color.White, fontSize = 14.sp)
                    }
                    if (stage == Stage.Pick && found.isEmpty()) {
                        Text("No plug in setup mode was found. Check that its light blinks quickly, keep the phone " +
                            "close, and try again.", color = Color.White, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    found.forEach { f ->
                        val name = known[f.advert.deviceId]?.let { "$it (already in Cozy Switch)" }
                            ?: "New plug …${f.advert.deviceId.takeLast(4)}"
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF1F2D3B)).clickable {
                                    job?.cancel(); chosen = f; stage = Stage.Wifi
                                }.padding(14.dp)
                        ) {
                            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("Model ${f.advert.productId}  ·  ${if (f.rssi > -70) "close" else "a bit far"}",
                                color = dim, fontSize = 12.sp)
                        }
                    }
                    Row {
                        if (stage == Stage.Pick) TextButton(onClick = { search() }) { Text("Search again") }
                        TextButton(onClick = { job?.cancel(); onClose() }) { Text("Cancel") }
                    }
                }
                Stage.Wifi -> {
                    chosen?.let { c ->
                        Text(known[c.advert.deviceId]?.let { "$it is back in setup mode. It keeps its name and automations." }
                            ?: "New plug …${c.advert.deviceId.takeLast(4)}", color = Color.White, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("Which Wi-Fi should the plug join?", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(ssid, { ssid = it }, label = { Text("Wi-Fi name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(pw, { pw = it }, label = { Text("Wi-Fi password") }, singleLine = true,
                        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { showPw = !showPw }) { Text(if (showPw) "Hide password" else "Show password") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(keepWifi, { keepWifi = it })
                        Text("Remember this Wi-Fi for the next plug (stays on this phone only)", color = dim, fontSize = 12.sp)
                    }
                    Text("The password is sent to the plug over Bluetooth, nowhere else.", color = dim, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { go() }, enabled = ssid.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Set up") }
                    TextButton(onClick = { stage = Stage.Pick }) { Text("Back") }
                }
                Stage.Working, Stage.Done, Stage.Failed -> {
                    log.forEach { Text("• $it", color = dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                    Spacer(Modifier.height(10.dp))
                    when (stage) {
                        Stage.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Working… keep the app open.", color = Color.White, fontSize = 14.sp)
                        }
                        Stage.Done -> {
                            Text("Done! The plug is on your Wi-Fi and in your plug list.", color = Color(0xFF7DDC9B),
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = onClose) { Text("Close") }
                        }
                        else -> {
                            Text(error ?: "Setup failed.", color = Color(0xFFFF8A80), fontSize = 14.sp)
                            Row {
                                if (chosen != null && session != null) TextButton(onClick = { stage = Stage.Wifi }) { Text("Try again") }
                                TextButton(onClick = onClose) { Text("Close") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The Wi-Fi the phone is on now, if Android tells us (it often doesn't without location access). */
private fun currentSsid(ctx: android.content.Context): String = try {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val info = caps?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }?.transportInfo as? WifiInfo
    info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: ""
} catch (_: Exception) { "" }
