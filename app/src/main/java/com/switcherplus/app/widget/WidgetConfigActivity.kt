package com.switcherplus.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.switcherplus.app.data.Switchers
import com.switcherplus.app.ui.AppTheme

/** Lets the user pick which device a new widget controls. Skipped when there's only one. */
class WidgetConfigActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Switchers.init(this)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, resultIntent())
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish(); return
        }

        val devices = Switchers.devices.value
        if (devices.size == 1) {
            choose(devices[0].config.id); return
        }

        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "איזה מכשיר הווידג'ט ידליק?",
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            )
                            if (devices.isEmpty()) {
                                Text("עוד לא נמצאו מכשירים. פתח קודם את האפליקציה כשהטלפון ב-Wi-Fi של הבית, ואז הוסף את הווידג'ט שוב.")
                            }
                            devices.forEach { d ->
                                Card(Modifier.fillMaxWidth().clickable { choose(d.config.id) }) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(d.config.name, style = MaterialTheme.typography.titleMedium)
                                        Text(d.config.modelName, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun choose(deviceId: String) {
        PowerWidget.assign(this, widgetId, deviceId)
        setResult(Activity.RESULT_OK, resultIntent())
        finish()
    }
}
