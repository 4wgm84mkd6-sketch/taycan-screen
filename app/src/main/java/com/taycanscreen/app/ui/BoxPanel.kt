package com.taycanscreen.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.box.BoxHealth
import com.taycanscreen.app.log.AppLog

/** Box health: inventory of installed apps, boot starters, memory/storage, with remove/settings buttons. */
@Composable
fun BoxPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val apps = remember(refresh) { BoxHealth.apps(context) }
    val s = remember(apps) { BoxHealth.summary(context, apps) }

    fun open(intent: Intent, what: String) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppLog.error("box", "Couldn't open $what", e)
            Toast.makeText(context, "Couldn't open $what", Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(12.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Box health", color = Palette.TextDim, fontSize = 16.sp)
                Text(
                    "Up %d min · RAM %d of %d MB free · storage %.1f of %.1f GB free".format(
                        s.uptimeSec / 60, s.ramFreeMb, s.ramTotalMb, s.storageFreeGb, s.storageTotalGb,
                    ),
                    color = Palette.Text, fontSize = 17.sp,
                )
                Text(
                    "${s.appCount} apps · ${s.addedLater} added later · ${s.bootStarters} start when the box boots",
                    color = Palette.Text, fontSize = 17.sp,
                )
                Text(
                    "Tap Record inventory, then Send log to Claude (Setup). Please don't disable preinstalled apps " +
                        "until Claude has reviewed the list: some keep the car link, Bluetooth, GPS or 4G working.",
                    color = Palette.Warn, fontSize = 15.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallAction("Record inventory") {
                        BoxHealth.logInventory(context)
                        Toast.makeText(context, "Inventory written to the log", Toast.LENGTH_SHORT).show()
                    }
                    SmallAction("Refresh") { refresh++ }
                }
            }
        }
        items(apps, key = { it.pkg }) { a ->
            Row(
                Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(10.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, color = if (a.enabled) Palette.Text else Palette.TextDim, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(a.pkg, color = Palette.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    val tags = listOfNotNull(
                        if (a.preinstalled) "preinstalled" else "added later",
                        if (!a.enabled) "disabled" else null,
                        if (a.startsAtBoot) "starts at boot" else null,
                        if (a.persistent) "always running" else null,
                        "${a.sizeBytes / (1024 * 1024)} MB",
                    )
                    Text(tags.joinToString(" · "), color = if (a.startsAtBoot) Palette.Warn else Palette.TextDim, fontSize = 13.sp)
                }
                if (!a.preinstalled && a.pkg != context.packageName) {
                    SmallAction("Uninstall") { open(BoxHealth.uninstallIntent(a.pkg), "uninstall") }
                }
                SmallAction("App settings") { open(BoxHealth.settingsIntent(a.pkg), "app settings") }
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHigh, contentColor = Palette.Text),
    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}
