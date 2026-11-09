package com.cozyswitch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cozyswitch.app.BuildConfig
import com.cozyswitch.app.data.Plug
import com.cozyswitch.app.data.Plugs
import com.cozyswitch.app.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0F1720)
private val CardBg = Color(0xFF1A2633)
private val Accent = Color(0xFFFFC94A)
private val OnGreen = Color(0xFF22A35A)
private val Dim = Color.White.copy(alpha = 0.6f)

@Composable
fun MainScreen() {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color(0xFF14202C))) {
        Screen()
    }
}

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val plugs = remember { mutableStateListOf<Plug>().apply { addAll(Plugs.load(ctx)) } }
    val busy = remember { mutableStateListOf<String>() }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var onWifi by remember { mutableStateOf(Plugs.onWifi(ctx)) }
    var editing by remember { mutableStateOf<Plug?>(null) }
    var addingIp by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf(Plugs.accountEmail(ctx)) }
    val cloudReady = plugs.any { it.hasCloud }

    // Redraw when the widget (or anything else) changes the saved list.
    DisposableEffect(Unit) {
        val l: () -> Unit = {
            scope.launch(Dispatchers.Main) { plugs.clear(); plugs.addAll(Plugs.load(ctx)) }
        }
        Plugs.listeners += l
        onDispose { Plugs.listeners -= l }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            onWifi = Plugs.onWifi(ctx)
            Plugs.syncCloud(ctx) // relay addresses can change
            Plugs.refresh(ctx)
            refreshing = false
        }
    }

    fun scan() {
        if (scanning) return
        scanning = true; scanProgress = 0f; message = null
        scope.launch {
            onWifi = Plugs.onWifi(ctx)
            val r = Plugs.scan(ctx) { p -> scope.launch(Dispatchers.Main) { scanProgress = p } }
            scanning = false
            message = when {
                !onWifi -> "Connect to your home Wi-Fi first."
                r.found == 0 -> "No CozyLife plugs answered. Make sure they're set up in the CozyLife app and on the same Wi-Fi."
                r.added == 0 -> "Found ${r.found} plug${if (r.found == 1) "" else "s"}. All of them were already here."
                else -> "Found ${r.found}, added ${r.added} new. Tap a name to rename it."
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cozy Switch", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("v${BuildConfig.VERSION_NAME}", color = Dim, fontSize = 12.sp)
            }
            IconButton(onClick = { refresh() }, enabled = !refreshing) {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                else Icon(Icons.Filled.Refresh, "Refresh", tint = Color.White)
            }
            IconButton(onClick = {
                scope.launch { message = Updater.checkNow(ctx) }
            }) { Icon(Icons.Filled.SystemUpdate, "Check for update", tint = Color.White) }
        }

        if (!onWifi && cloudReady) {
            Text("Away from home: switching through CozyLife's cloud.", color = Dim, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp))
        } else if (!onWifi) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF3A2A12)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.WifiOff, null, tint = Accent)
                Spacer(Modifier.width(10.dp))
                Text("You're not on Wi-Fi. Sign in to CozyLife below to switch plugs from anywhere.",
                    color = Color.White, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
        }

        message?.let {
            Text(it, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg)
                    .clickable { message = null }.padding(12.dp))
            Spacer(Modifier.height(10.dp))
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (plugs.isEmpty() && !scanning) {
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(18.dp)
                    ) {
                        Text("No plugs yet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Your plugs must already be set up in the CozyLife app (that's where the Bluetooth " +
                                "pairing happens). Then connect this phone to the same Wi-Fi and tap Find plugs.",
                            color = Dim, fontSize = 14.sp
                        )
                    }
                }
            }
            items(plugs, key = { it.did }) { p ->
                PlugCard(
                    p, busy = p.did in busy,
                    onToggle = { want ->
                        busy += p.did
                        scope.launch {
                            val r = Plugs.switch(ctx, p.did, want)
                            busy -= p.did
                            if (r == null) message = Plugs.lastCloudError?.let { "${p.name}: $it" }
                                ?: "${p.name} didn't answer. Is it plugged in and on the Wi-Fi?"
                        }
                    },
                    onEdit = { editing = p }
                )
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(16.dp)) {
                    Text("Away from home", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    if (account != null) {
                        Text("Signed in to CozyLife as $account. When the plugs can't be reached on Wi-Fi, " +
                            "Cozy Switch goes through CozyLife's cloud, like the CozyLife app.", color = Dim, fontSize = 13.sp)
                        TextButton(onClick = { Plugs.signOut(ctx); account = null }) { Text("Sign out") }
                    } else {
                        Text("Sign in with your CozyLife account to switch plugs from anywhere. " +
                            "Your password is only used to sign in and isn't saved.", color = Dim, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { signingIn = true }) { Text("Sign in to CozyLife") }
                    }
                }
            }
            item {
                Text(
                    "Widget: long-press your home screen → Widgets → Cozy Switch. Tap a plug on the widget to switch it.",
                    color = Dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }

        if (scanning) {
            Text("Looking through your Wi-Fi…", color = Dim, fontSize = 13.sp)
            LinearProgressIndicator(
                progress = { scanProgress },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Accent, trackColor = Color.White.copy(alpha = 0.12f)
            )
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { scan() }, enabled = !scanning, modifier = Modifier.weight(1f)) { Text("Find plugs") }
            OutlinedButton(onClick = { addingIp = true }, modifier = Modifier.weight(1f)) {
                Text("Add by IP", color = Color.White)
            }
        }
    }

    editing?.let { p ->
        EditDialog(
            p,
            onDismiss = { editing = null },
            onSave = { name -> Plugs.rename(ctx, p.did, name); editing = null },
            onMove = { by -> Plugs.move(ctx, p.did, by) },
            onRemove = { Plugs.remove(ctx, p.did); editing = null },
        )
    }

    if (signingIn) {
        var email by remember { mutableStateOf("") }
        var pw by remember { mutableStateOf("") }
        var working by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!working) signingIn = false },
            title = { Text("Sign in to CozyLife") },
            text = {
                Column {
                    Text("Use the same email and password as in the CozyLife app.", fontSize = 13.sp, color = Dim)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    err?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
            },
            confirmButton = {
                Button(enabled = !working && email.isNotBlank() && pw.isNotEmpty(), onClick = {
                    working = true; err = null
                    scope.launch {
                        try {
                            val n = Plugs.signIn(ctx, email, pw)
                            account = Plugs.accountEmail(ctx)
                            signingIn = false
                            message = if (n == 0) "Signed in, but the account has no plugs."
                                else "Signed in. $n plug${if (n == 1) "" else "s"} can now be switched from anywhere."
                        } catch (e: Exception) {
                            err = e.message ?: "Sign-in failed."
                        }
                        working = false
                    }
                }) { Text(if (working) "Signing in…" else "Sign in") }
            },
            dismissButton = { TextButton(enabled = !working, onClick = { signingIn = false }) { Text("Cancel") } }
        )
    }

    if (addingIp) {
        var ip by remember { mutableStateOf("192.168.") }
        var working by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { addingIp = false },
            title = { Text("Add a plug by its address") },
            text = {
                Column {
                    Text("You can find the plug's IP address in your router's list of connected devices.",
                        fontSize = 13.sp, color = Dim)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(ip, { ip = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    err?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
            },
            confirmButton = {
                Button(enabled = !working, onClick = {
                    working = true; err = null
                    scope.launch {
                        val ok = Plugs.addByIp(ctx, ip)
                        working = false
                        if (ok) addingIp = false else err = "No CozyLife plug answered at $ip."
                    }
                }) { Text(if (working) "Checking…" else "Add") }
            },
            dismissButton = { TextButton(onClick = { addingIp = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PlugCard(p: Plug, busy: Boolean, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val on = p.on == true
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg)
            .clickable(enabled = !busy && (p.on != null || p.hasCloud)) { onToggle(!on) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape)
                .background(if (on) Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Power, null, tint = if (on) Accent else Dim)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).clickable { onEdit() }) {
            Text(p.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when (p.on) { true -> "On"; false -> "Off"; null -> "Not answering" } +
                    when (p.via) { "wifi" -> "  ·  via Wi-Fi"; "cloud" -> "  ·  via cloud ☁"; else -> "" },
                color = if (p.on == null) Color(0xFFFFB27A) else Dim, fontSize = 12.sp
            )
            val hint = when {
                p.on == null && p.ip.isNotEmpty() || p.on == null && p.hasCloud ->
                    "Unplug it from the wall for 10 seconds, then plug it back in."
                p.wifiStuck && p.via == "cloud" ->
                    "Its Wi-Fi connection is stuck, so the cloud is used. Unplugging it for 10 s fixes that."
                else -> null
            }
            hint?.let { Text(it, color = Color(0xFFFFB27A), fontSize = 12.sp) }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp, color = Accent)
            Spacer(Modifier.width(12.dp))
        }
        Switch(
            checked = on,
            onCheckedChange = { onToggle(it) },
            enabled = !busy,
            colors = SwitchDefaults.colors(checkedTrackColor = OnGreen, checkedThumbColor = Color.White)
        )
    }
}

@Composable
private fun EditDialog(
    p: Plug,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var name by remember { mutableStateOf(p.name) }
    var confirmRemove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit plug") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onMove(-1) }) { Text("Move up") }
                    OutlinedButton(onClick = { onMove(1) }) { Text("Move down") }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { if (confirmRemove) onRemove() else confirmRemove = true }) {
                    Text(if (confirmRemove) "Tap again to remove" else "Remove from app", color = Color(0xFFFF8A80))
                }
                Text("Address ${p.ip}", color = Dim, fontSize = 12.sp)
            }
        },
        confirmButton = { Button(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
