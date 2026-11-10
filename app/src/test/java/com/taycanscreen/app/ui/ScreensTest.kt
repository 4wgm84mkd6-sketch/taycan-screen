package com.taycanscreen.app.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf
import com.taycanscreen.app.car.SpeedLimit
import com.taycanscreen.app.music.SpotifyController
import com.taycanscreen.app.nav.LatLon
import com.taycanscreen.app.nav.Nav
import com.taycanscreen.app.nav.Place
import com.taycanscreen.app.nav.Route
import com.taycanscreen.app.nav.Step
import com.taycanscreen.app.obd.LinkState
import com.taycanscreen.app.obd.ObdEngine
import com.taycanscreen.app.obd.ObdStatus
import com.taycanscreen.app.obd.ObdViewModel
import com.taycanscreen.app.obd.Reading
import com.taycanscreen.app.obd.Signal
import com.taycanscreen.app.obd.Taycan
import com.taycanscreen.app.util.Mono
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the real screens with fixed values, drawn on the build server at the car window's size
 * (1602×720 px). They let a change be looked at before it reaches the car. The map is a flat
 * placeholder (its native code only runs on the box).
 *
 * Run with: gradle testReleaseUnitTest -Pscreens --tests '*ScreensTest*'
 * Output: app/build/outputs/roborazzi/​*.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1068dp-h480dp-land-hdpi", application = Application::class)
class ScreensTest {
    /** The test screen's host activity isn't in the app's manifest; tell Robolectric about it. */
    @get:Rule(order = 0)
    val registerActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun reading(sig: Signal, value: Double) = sig.id to Reading(value, "", Mono.ms())

    private fun driving(speedKmh: Double = 75.6, gear: Double = 8.0, amps: Double = -62.0) = mapOf(
        reading(Taycan.SPEED_DIR, speedKmh), reading(Taycan.GEAR, gear), reading(Taycan.SOC_DISPLAY, 56.4),
        reading(Taycan.HV_VOLTAGE_1818, 640.0), reading(Taycan.HV_CURRENT, amps), reading(Taycan.RANGE, 164.0),
        reading(Taycan.PACK_T_MAX, 31.0), reading(Taycan.PACK_T_MIN, 29.0), reading(Taycan.LV_SOC, 95.0),
        reading(Taycan.LV_VOLTAGE, 13.4), reading(Taycan.HV_SOH, 98.8),
        reading(Taycan.TIRE_FL, 2.9), reading(Taycan.TIRE_FR, 2.9), reading(Taycan.TIRE_RL, 2.7), reading(Taycan.TIRE_RR, 2.7),
    )

    private val live = ObdStatus(LinkState.Live, "Reading the car", adapterName = "OBDLink MX+")

    private fun playing() = SpotifyController.NowPlaying(
        title = "After Hours", artist = "Kehlani", album = "CRASH", paused = false,
        durationMs = 201_000, positionMs = 64_000, positionAt = Mono.ms(), imageUri = null,
    )

    @Before
    fun setUp() {
        UiEvents.preview = true
        UiEvents.openPanel.value = null
        ObdEngine.attach(ApplicationProvider.getApplicationContext())
        ObdEngine.preview(driving(), live)
        SpotifyController.preview(SpotifyController.Status(SpotifyController.Link.Connected), playing())
        SpeedLimit.preview(45)
    }

    private fun shoot(name: String, night: Boolean = true, panel: String? = null) {
        UiEvents.openPanel.value = panel
        // The screens have animations that never end (scrolling titles, the GPS reticle), so time is
        // stepped by hand: draw, let 0.7 s pass (panels finish sliding in), take the picture.
        rule.mainClock.autoAdvance = false
        rule.setContent { Drive(night) }
        rule.mainClock.advanceTimeBy(700)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun Drive(night: Boolean) {
        TaycanTheme {
            DriveScreen(
                obd = ObdViewModel(ApplicationProvider.getApplicationContext()),
                colors = if (night) DriveColors.Night else DriveColors.Day,
                themeMode = if (night) ThemeMode.Night else ThemeMode.Day,
                locationGranted = true,
                onCycleTheme = {}, onOpenVehicle = {}, onOpenSetup = {},
            )
        }
    }

    @Test fun driveNight() = shoot("01-drive-night")

    @Test fun driveDay() = shoot("02-drive-day", night = false)

    @Test fun driveOverTheLimit() {
        SpeedLimit.preview(35)
        ObdEngine.preview(driving(speedKmh = 72.0, amps = 48.0), live)
        shoot("03-drive-over-limit-regen")
    }

    @Test fun connecting() {
        ObdEngine.preview(emptyMap(), ObdStatus(LinkState.Connecting, "Connecting…", downSince = Mono.ms()))
        SpotifyController.preview(SpotifyController.Status(SpotifyController.Link.Connecting, "Connecting to Spotify…"), null)
        SpeedLimit.preview(null)
        shoot("04-connecting")
    }

    @Test fun parked() {
        ObdEngine.preview(driving(speedKmh = 0.0, gear = 5.0, amps = -3.0), live)
        SpeedLimit.preview(null)
        shoot("05-parked-day", night = false)
    }

    @Test fun route() {
        // A short route through Honolulu: right on South Street, then straight on Alapai Street.
        val a = LatLon(21.3000, -157.8600); val b = LatLon(21.3010, -157.8600)
        val c2 = LatLon(21.3010, -157.8560); val d = LatLon(21.3050, -157.8560)
        val steps = listOf(
            Step(a, "depart", null, "Ala Moana Boulevard", null, null, 111.0, 0.0),
            Step(b, "turn", "right", "South Street", null, null, 415.0, 90.0),
            Step(c2, "turn", "left", "Alapai Street", null, null, 445.0, 0.0),
            Step(d, "arrive", null, "", null, null, 0.0, null),
        )
        Nav.preview(Place("Honolulu Hale", "530 S King St", d.lat, d.lon), Route(listOf(a, b, c2, d), 971.0, 240.0, steps), LatLon(21.3004, -157.8600))
        try {
            shoot("10-route")
        } finally {
            Nav.preview(null, null, null)
        }
    }

    @Test fun tireWarning() {
        val low = driving() + mapOf(
            reading(Taycan.TIRE_RL, 2.2),
            "TAYCAN_TP_SET_RL" to Reading(2.9, "", Mono.ms()),
            "TAYCAN_TP_SET_FL" to Reading(2.9, "", Mono.ms()),
        )
        ObdEngine.preview(low, live)
        shoot("11-tire-warning")
    }

    @Test fun tires() {
        val all = driving() + listOf("FL", "FR", "RL", "RR").flatMap { c ->
            listOf("TAYCAN_TP_SET_$c" to Reading(2.9, "", Mono.ms()), "TAYCAN_TT_$c" to Reading(31.0, "", Mono.ms()))
        }
        ObdEngine.preview(all + mapOf(reading(Taycan.TIRE_RL, 2.2)), live)
        UiEvents.openPanel.value = "ledger"
        shoot("12-ledger-default-tab", panel = "ledger")
    }

    @Test fun search() = shoot("06-search", panel = "search")

    @Test fun music() = shoot("07-music", panel = "music")

    @Test fun audio() = shoot("08-audio", panel = "audio")

    @Test fun ledger() = shoot("09-ledger", panel = "ledger")
}
