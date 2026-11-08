package com.taycanscreen.app

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.log.LogSharer
import com.taycanscreen.app.ui.Palette
import com.taycanscreen.app.ui.TaycanTheme
import com.taycanscreen.app.ui.WindowNumbers
import com.taycanscreen.app.ui.windowNumbers
import com.taycanscreen.app.update.InstallResultReceiver
import com.taycanscreen.app.update.UpdateState
import com.taycanscreen.app.update.UpdateViewModel
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val updates: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            TaycanTheme {
                MainScreen(
                    updates = updates,
                    window = windowNumbers(),
                    onShareLog = {
                        try {
                            if (!LogSharer.share(this)) toast("No log file yet")
                        } catch (e: Exception) {
                            AppLog.error("log", "Share failed", e)
                            toast("Share failed: ${e.message}")
                        }
                    },
                    onOpenInstallSetting = {
                        try {
                            startActivity(updates.updater.installPermissionIntent())
                        } catch (e: Exception) {
                            AppLog.error("update", "Couldn't open install setting", e)
                            toast("Couldn't open Settings: ${e.message}")
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updates.onResume()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

private val LargeButtonHeight = 72.dp

@Composable
private fun MainScreen(
    updates: UpdateViewModel,
    window: WindowNumbers,
    onShareLog: () -> Unit,
    onOpenInstallSetting: () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(16.dp),
    ) {
        val contentW = maxWidth
        val contentH = maxHeight
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier
                    .width(minOf(440.dp, contentW * 0.45f))
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Taycan Screen", color = Palette.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Build ${BuildConfig.VERSION_CODE}",
                    color = Palette.TextDim,
                    fontSize = 20.sp,
                )
                UpdatePanel(updates, onOpenInstallSetting)
                BigButton("Share log", onClick = onShareLog)
                ScreenPanel(window, contentW, contentH)
            }
            LogViewer(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun UpdatePanel(updates: UpdateViewModel, onOpenInstallSetting: () -> Unit) {
    val state by updates.state.collectAsState()
    val installResult by InstallResultReceiver.lastResult.collectAsState()

    Panel {
        when (val s = state) {
            is UpdateState.Available -> {
                StatusText("Build ${s.release.versionCode} is available.", Palette.Accent)
                BigButton("Install build ${s.release.versionCode}") { updates.install(s.release) }
            }
            UpdateState.NeedsPermission -> {
                StatusText(
                    "Android needs your OK first: tap the button, turn on \"Allow from this source\", then come back.",
                    Palette.Warn,
                )
                BigButton("Open install setting", onClick = onOpenInstallSetting)
            }
            is UpdateState.Downloading -> {
                StatusText("Downloading… ${(s.progress * 100).roundToInt()}%")
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
            }
            UpdateState.Installing -> StatusText(installResult ?: "Installing…")
            else -> {
                when (s) {
                    UpdateState.Checking -> StatusText("Checking…")
                    is UpdateState.UpToDate -> StatusText("Up to date (latest is build ${s.latest}).", Palette.Accent)
                    is UpdateState.Failed -> StatusText("Couldn't update: ${s.message}", Palette.Error)
                    else -> if (installResult != null) StatusText(installResult!!)
                }
                BigButton("Check for update", enabled = s != UpdateState.Checking) { updates.check() }
            }
        }
    }
}

@Composable
private fun ScreenPanel(window: WindowNumbers, contentW: Dp, contentH: Dp) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val wPx = with(density) { contentW.toPx() }.roundToInt()
    val hPx = with(density) { contentH.toPx() }.roundToInt()
    val summary = "App area ${contentW.value.roundToInt()}×${contentH.value.roundToInt()} dp " +
        "($wPx×$hPx px, inside 16 dp padding) · " +
        "window ${window.windowWidthPx}×${window.windowHeightPx} px · " +
        "max ${window.maxWidthPx}×${window.maxHeightPx} px · " +
        "${config.densityDpi} dpi (scale ${"%.2f".format(density.density)}) · " +
        "font scale ${"%.2f".format(density.fontScale)}"
    var logged by remember { mutableStateOf("") }
    LaunchedEffect(summary) {
        if (summary != logged) {
            AppLog.info("screen", summary)
            logged = summary
        }
    }
    Panel {
        Text("Screen", color = Palette.TextDim, fontSize = 16.sp)
        Text(summary, color = Palette.Text, fontSize = 16.sp)
    }
}

@Composable
private fun LogViewer(modifier: Modifier) {
    val lines by AppLog.lines.collectAsState()
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(
        modifier
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Log (${lines.size} lines)", color = Palette.TextDim, fontSize = 18.sp)
            Button(
                onClick = { follow = !follow },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.SurfaceHigh, contentColor = Palette.Text),
            ) { Text(if (follow) "Following newest" else "Paused: tap to follow", fontSize = 16.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        line,
                        color = lineColor(line),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

private fun lineColor(line: String): Color {
    val kind = line.substringAfter(' ').substringAfter(' ').take(4).trim()
    return when (kind) {
        "ERR" -> Palette.Error
        "WARN" -> Palette.Warn
        "TX" -> Color(0xFF7CC4FF)
        "RX" -> Palette.Accent
        else -> Palette.Text
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun StatusText(text: String, color: Color = Palette.Text) {
    Text(text, color = color, fontSize = 18.sp)
}

@Composable
private fun BigButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = LargeButtonHeight),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.SurfaceHigh,
            contentColor = Palette.Text,
            disabledContainerColor = Palette.Surface,
            disabledContentColor = Palette.TextDim,
        ),
    ) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
}
