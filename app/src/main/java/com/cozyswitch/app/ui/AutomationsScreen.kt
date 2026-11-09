package com.cozyswitch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cozyswitch.app.auto.Scheduler
import com.cozyswitch.app.data.Automation
import com.cozyswitch.app.data.Automations
import com.cozyswitch.app.data.Plug
import com.cozyswitch.app.data.Plugs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime

private val CardBg = Color(0xFF1A2633)
private val Accent = Color(0xFFFFC94A)
private val OnGreen = Color(0xFF22A35A)
private val Dim = Color.White.copy(alpha = 0.6f)

@Composable
fun AutomationsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = remember { mutableStateListOf<Automation>().apply { addAll(Automations.load(ctx)) } }
    val plugs = remember { mutableStateListOf<Plug>() }
    var editing by remember { mutableStateOf<Automation?>(null) }
    var exact by remember { mutableStateOf(Scheduler.exactAllowed(ctx)) }
    var note by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val l: () -> Unit = { scope.launch(Dispatchers.Main) { list.clear(); list.addAll(Automations.load(ctx)) } }
        Automations.listeners += l
        onDispose { Automations.listeners -= l }
    }
    LifecycleResumeEffect(Unit) {
        plugs.clear(); plugs.addAll(Plugs.load(ctx))
        exact = Scheduler.exactAllowed(ctx)
        Scheduler.rescheduleAll(ctx) // picks up the exact-timing switch if it was just turned on
        onPauseOrDispose { }
    }

    fun save(a: Automation) {
        Automations.put(ctx, a)
        Scheduler.schedule(ctx, a)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Automations", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))

        if (!exact) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF3A2A12)).padding(12.dp)
            ) {
                Text("For automations to run on the exact minute, Android needs one switch turned on " +
                    "(Alarms & reminders). Without it they can be a few minutes late.", color = Color.White, fontSize = 14.sp)
                TextButton(onClick = { Scheduler.askExact(ctx) }) { Text("Turn it on") }
            }
        }
        note?.let {
            Text(it, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp))
                    .background(CardBg).clickable { note = null }.padding(12.dp))
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (list.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(18.dp)) {
                        Text("No automations yet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Make one to turn plugs on or off at set times, for example the heater on at 06:30 on " +
                            "weekdays. Make a second one to turn them off again.", color = Dim, fontSize = 14.sp)
                    }
                }
            }
            items(list.sortedBy { it.hour * 60 + it.minute }, key = { it.id }) { a ->
                AutomationCard(a, plugs,
                    onToggle = { save(a.copy(enabled = it)) },
                    onEdit = { editing = a })
            }
            item {
                Text("Automations run from this phone. It needs to be switched on with internet " +
                    "(home Wi-Fi, or anywhere if you're signed in to CozyLife).",
                    color = Dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        Button(
            onClick = { editing = Automation(plugs = emptySet()) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("New automation")
        }
    }

    editing?.let { a ->
        Editor(
            start = a, plugs = plugs, isNew = list.none { it.id == a.id },
            onDismiss = { editing = null },
            onSave = { saved ->
                save(saved); editing = null
                note = saved.next(ZonedDateTime.now())?.let { "Next run ${whenText(it)}." }
            },
            onDelete = { Scheduler.cancel(ctx, a.id); Automations.delete(ctx, a.id); editing = null },
            onRunNow = { saved -> save(saved); Scheduler.runNow(ctx, saved.id); editing = null
                note = "Running ${saved.name.ifBlank { "it" }} now." },
        )
    }
}

private fun whenText(t: ZonedDateTime): String {
    val now = ZonedDateTime.now()
    val mins = Duration.between(now, t).toMinutes()
    val day = when (t.toLocalDate()) {
        now.toLocalDate() -> "today"
        now.toLocalDate().plusDays(1) -> "tomorrow"
        else -> Automation.DAY_SHORT[t.dayOfWeek.value - 1]
    }
    val inText = if (mins < 60) "in $mins min" else "in ${mins / 60} h ${mins % 60} min"
    return "$day at %02d:%02d ($inText)".format(t.hour, t.minute)
}

@Composable
private fun AutomationCard(a: Automation, plugs: List<Plug>, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val names = plugs.filter { it.did in a.plugs }.joinToString { it.name }.ifEmpty { "No plugs chosen" }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).clickable { onEdit() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.time, color = if (a.enabled) Color.White else Dim, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(if (a.on) "ON" else "OFF", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (a.on) OnGreen else Color.White.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Text((if (a.name.isNotBlank()) "${a.name}  ·  " else "") + a.daysText(), color = Dim, fontSize = 13.sp)
            Text(names, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 2)
        }
        Switch(a.enabled, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = OnGreen, checkedThumbColor = Color.White))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Editor(
    start: Automation,
    plugs: List<Plug>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Automation) -> Unit,
    onDelete: () -> Unit,
    onRunNow: (Automation) -> Unit,
) {
    var name by remember { mutableStateOf(start.name) }
    var on by remember { mutableStateOf(start.on) }
    var days by remember { mutableStateOf(start.days) }
    var chosen by remember { mutableStateOf(start.plugs) }
    var confirmDelete by remember { mutableStateOf(false) }
    val time = rememberTimePickerState(start.hour, start.minute, is24Hour = true)

    fun result() = start.copy(
        name = name.trim(), on = on, hour = time.hour, minute = time.minute,
        days = days, plugs = chosen, enabled = true,
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 720.dp).clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF16212C)).verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Text(if (isNew) "New automation" else "Edit automation", color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            Label("Turn the plugs")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Segment("ON", on, OnGreen, Modifier.weight(1f)) { on = true }
                Segment("OFF", !on, Color.White.copy(alpha = 0.25f), Modifier.weight(1f)) { on = false }
            }

            Spacer(Modifier.height(16.dp))
            Label("At")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimeInput(state = time) }

            Label("On these days")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Automation.DAY_SHORT.forEachIndexed { i, d ->
                    val day = i + 1
                    val sel = day in days
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (sel) Accent else Color.Transparent)
                            .border(1.dp, if (sel) Accent else Color.White.copy(alpha = 0.3f), CircleShape)
                            .clickable { days = if (sel) days - day else days + day },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(d.take(2), color = if (sel) Color(0xFF14202C) else Color.White, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { days = (1..7).toSet() }) { Text("Every day") }
                TextButton(onClick = { days = setOf(1, 2, 3, 4, 5) }) { Text("Weekdays") }
                TextButton(onClick = { days = emptySet() }) { Text("Once") }
            }
            if (days.isEmpty()) Text("No days picked: it runs once, at the next ${"%02d:%02d".format(time.hour, time.minute)}.",
                color = Dim, fontSize = 12.sp)

            Spacer(Modifier.height(12.dp))
            Label("Plugs")
            if (plugs.isEmpty()) Text("Add plugs on the Plugs tab first.", color = Dim, fontSize = 14.sp)
            plugs.forEach { p ->
                val sel = p.did in chosen
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { chosen = if (sel) chosen - p.did else chosen + p.did }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(sel, { chosen = if (sel) chosen - p.did else chosen + p.did },
                        colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = Color(0xFF14202C)))
                    Text(p.name, color = Color.White, fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSave(result()) }, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onRunNow(result()) }, enabled = chosen.isNotEmpty()) { Text("Save & run now") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
            if (!isNew) {
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(if (confirmDelete) "Tap again to delete" else "Delete automation", color = Color(0xFFFF8A80))
                }
            }
        }
    }
}

@Composable
private fun Label(t: String) {
    Text(t, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Segment(text: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(14.dp))
            .background(if (selected) color else Color.Transparent)
            .border(1.dp, if (selected) color else Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(vertical = 12.dp)
    )
}

