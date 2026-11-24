package com.switcherplus.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.switcherplus.app.R
import com.switcherplus.app.data.Device
import com.switcherplus.app.data.Switchers
import kotlinx.coroutines.delay

private val QUICK_TIMERS = listOf(5, 15, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    batteryOptimized: Boolean,
    onGuardSettingsChanged: () -> Unit,
    onFixBattery: () -> Unit,
) {
    val devices by Switchers.devices.collectAsState()
    val scanning by Switchers.scanning.collectAsState()
    val log by Switchers.log.collectAsState()
    var showLog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("סוויצ'ר פלוס", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = { showLog = !showLog }) {
                        Text(if (showLog) "מכשירים" else "היסטוריה")
                    }
                    IconButton(onClick = { Switchers.rescan() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "חיפוש מחדש")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (showLog) {
                item { LogSection(log) }
                return@LazyColumn
            }
            if (batteryOptimized && devices.any { it.config.guardEnabled }) {
                item { BatteryCard(onFixBattery) }
            }
            if (devices.isEmpty()) {
                item { EmptyState(scanning) }
            }
            items(devices, key = { it.config.id }) { d ->
                DeviceCard(d, onGuardSettingsChanged)
            }
        }
    }
}

@Composable
private fun EmptyState(scanning: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (scanning) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("מחפש מכשירי Switcher ברשת…", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("לא נמצאו מכשירים", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "ודא שהטלפון מחובר ל-Wi-Fi של הבית, אותה רשת שהשקע מחובר אליה. " +
                    "השקע משדר את מצבו כל כמה שניות, והוא יופיע כאן אוטומטית.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BatteryCard(onFix: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("כדי שהכיבוי האוטומטי יעבוד גם כשהמסך כבוי", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("צריך לאשר לאפליקציה לרוץ ברקע בלי הגבלת חיסכון בסוללה.")
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onFix) { Text("לאשר ריצה ברקע") }
        }
    }
}

@Composable
private fun DeviceCard(d: Device, onGuardSettingsChanged: () -> Unit) {
    val c = d.config
    val s = d.status
    var renaming by remember { mutableStateOf(false) }

    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    val elapsed = if (s.updatedAtMs > 0) (now - s.updatedAtMs) / 1000 else 0
    val left = (s.timeLeftSec - elapsed).coerceAtLeast(0)

    val onColor = Color(0xFFFFA000)
    val btnColor by animateColorAsState(
        if (s.on) onColor else MaterialTheme.colorScheme.surfaceVariant, label = "btn"
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { renaming = true }) {
                    Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${c.modelName} · ${c.ip}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier.size(84.dp).clip(CircleShape).background(btnColor)
                        .clickable(enabled = !s.busy) {
                            if (s.on) Switchers.turnOff(c.id) else Switchers.turnOn(c.id)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (s.busy) CircularProgressIndicator(Modifier.size(32.dp))
                    else Icon(
                        painterResource(R.drawable.ic_power), contentDescription = if (s.on) "כבה" else "הדלק",
                        modifier = Modifier.size(40.dp),
                        tint = if (s.on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            val statusLine = when {
                !s.reachable && s.updatedAtMs == 0L -> "מתחבר…"
                !s.reachable -> "לא מגיב — בדוק שהטלפון ברשת הביתית"
                s.on -> "דלוק · ${s.powerWatts}W"
                else -> "כבוי"
            }
            Text(statusLine, style = MaterialTheme.typography.titleMedium)
            if (s.on && s.reachable) {
                val extra = when {
                    s.noLimitThisRun && left == 0L -> "ללא הגבלת זמן עד הכיבוי הבא"
                    left > 0 -> "ייכבה בעוד ${fmt(left)}"
                    else -> "דולק כבר ${fmt(s.timeOnSec + elapsed)}"
                }
                Text(extra, color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            s.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            Text("הדלקה לזמן מוגדר", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QUICK_TIMERS.forEach { m ->
                    OutlinedButton(
                        onClick = { Switchers.turnOnFor(c.id, m) },
                        enabled = !s.busy,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text("$m׳") }
                }
            }
            TextButton(onClick = { Switchers.turnOnNoLimit(c.id) }, enabled = !s.busy) {
                Text("הדלק בלי הגבלה (חד-פעמי)")
            }

            Spacer(Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("כיבוי אוטומטי", fontWeight = FontWeight.Bold)
                            Text(
                                "גם כשמדליקים מהכפתור בשקע או מאפליקציה אחרת",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = c.guardEnabled, onCheckedChange = {
                            Switchers.setGuard(c.id, it); onGuardSettingsChanged()
                        })
                    }
                    if (c.guardEnabled) {
                        Spacer(Modifier.height(8.dp))
                        LimitStepper(c.limitMinutes,
                            onChange = { Switchers.setLimit(c.id, it) },
                            onDone = { Switchers.applyLimitNow(c.id) })
                    }
                }
            }
        }
    }

    if (renaming) RenameDialog(c.name, onDismiss = { renaming = false }, onSave = {
        Switchers.rename(c.id, it); renaming = false
    }, onForget = { Switchers.forget(c.id); renaming = false; onGuardSettingsChanged() })
}

@Composable
private fun LimitStepper(value: Int, onChange: (Int) -> Unit, onDone: () -> Unit) {
    // apply to the device a moment after the user stops tapping
    LaunchedEffect(value) { delay(1500); onDone() }
    fun step(v: Int, up: Boolean): Int = when {
        up -> if (v < 10) v + 1 else if (v < 60) v + 5 else v + 15
        else -> if (v <= 10) v - 1 else if (v <= 60) v - 5 else v - 15
    }.coerceIn(1, 600)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()) {
        Text("לכבות אחרי", Modifier.weight(1f))
        FilledTonalButton(onClick = { onChange(step(value, false)) }, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(44.dp)) { Text("−", fontSize = 22.sp) }
        Text(
            "$value דק׳", Modifier.width(84.dp), textAlign = TextAlign.Center,
            fontSize = 20.sp, fontWeight = FontWeight.Bold,
        )
        FilledTonalButton(onClick = { onChange(step(value, true)) }, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(44.dp)) { Text("+", fontSize = 22.sp) }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onForget: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("שם המכשיר") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("השם נשמר רק באפליקציה הזו.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onForget) {
                    Text("הסר מכשיר מהרשימה", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("שמור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

@Composable
private fun LogSection(log: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("היסטוריה", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (log.isNotEmpty()) TextButton(onClick = { Switchers.clearLog() }) { Text("נקה") }
            }
            if (log.isEmpty()) Text("אין עדיין אירועים.")
            log.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
        }
    }
}

private fun fmt(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
