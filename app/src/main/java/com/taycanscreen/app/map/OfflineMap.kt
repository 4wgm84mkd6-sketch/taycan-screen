package com.taycanscreen.app.map

import android.content.Context
import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.net.NetMonitor
import com.taycanscreen.app.util.Friendly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Keeps the whole of Oʻahu's map on the box (owner, 2026-11-14: panning showed blank patches while
 * the map waited on 4G). MapLibre's offline region: style, fonts, icons and every vector tile of the
 * island down to street level, stored in the map's own database and used before the network.
 *
 * OpenFreeMap publishes new map data under a new address every so often; saved tiles under the old
 * address are then no longer used. So at each app start we read the current tile address and, if it
 * changed, replace the saved island with the new one (the log says how big it is).
 * Satellite pictures are not saved (they come from a different service).
 */
object OfflineMap {
    /** Oʻahu with a small margin: north, east, south, west. */
    private val OAHU: LatLngBounds = LatLngBounds.from(21.73, -157.62, 21.23, -158.30)
    private const val NAME = "oahu"
    /** OpenFreeMap's tiles stop at zoom 14; closer views are drawn from them. */
    private const val MAX_ZOOM = 14.0
    /** Our map is always drawn at more than 1× (density 1.49 × size), so it uses the 2× icons. */
    private const val PIXEL_RATIO = 2f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var region: OfflineRegion? = null

    /** Once per app start, after the start-up has settled; retries every 10 min until it's sorted. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            delay(30_000)
            while (true) {
                val settled = try {
                    check(app)
                } catch (e: Exception) {
                    AppLog.warn("map", "Saved map check failed: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
                if (settled) break
                delay(10 * 60_000L)
            }
        }
    }

    /** True when nothing more needs doing this app start (saved and current, or a download is running). */
    private suspend fun check(context: Context): Boolean {
        if (!NetMonitor.online.value || Friendly.clockIsWrong()) return false
        val tiles = withContext(Dispatchers.IO) { currentTileAddress() }
        val manager = OfflineManager.getInstance(context)
        val regions = listRegions(manager)
        var keep: OfflineRegion? = null
        for (r in regions) {
            val meta = try { JSONObject(String(r.metadata)) } catch (e: Exception) { JSONObject() }
            if (keep == null && meta.optString("name") == NAME && meta.optString("tiles") == tiles) {
                keep = r
            } else {
                AppLog.info("map", "Removing the saved map from older map data (${meta.optString("tiles").ifEmpty { "unknown" }})")
                r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = Unit
                    override fun onError(error: String) = AppLog.warn("map", "Couldn't remove an old saved map: $error")
                })
            }
        }
        val r = keep ?: createRegion(manager, tiles)
        val status = status(r)
        if (status != null && status.isComplete) {
            AppLog.info("map", "Oʻahu's map is saved on the box (${status.completedTileCount} tiles, ${mb(status.completedResourceSize)})")
            return true
        }
        download(r, resumed = keep != null)
        return true
    }

    private fun download(r: OfflineRegion, resumed: Boolean) {
        region = r
        AppLog.info("map", if (resumed) "Saving Oʻahu's map on the box: carrying on" else "Saving Oʻahu's map on the box: starting")
        var lastPct = -1
        var errors = 0
        r.setDeliverInactiveMessages(false)
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isComplete) {
                    r.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    r.setObserver(null)
                    AppLog.info("map", "Oʻahu's map is saved on the box (${status.completedTileCount} tiles, ${mb(status.completedResourceSize)})")
                    return
                }
                val req = status.requiredResourceCount
                if (req <= 0) return
                val pct = (100 * status.completedResourceCount / req).toInt()
                if (pct / 10 != lastPct / 10) {
                    lastPct = pct
                    AppLog.info("map", "Saving Oʻahu's map: $pct % (${status.completedResourceCount} of $req${if (status.isRequiredResourceCountPrecise) "" else "+"}, ${mb(status.completedResourceSize)})")
                }
            }

            override fun onError(error: OfflineRegionError) {
                // The map library keeps trying by itself (e.g. after a drop in signal); log the first few.
                if (++errors <= 5) AppLog.warn("map", "Saving Oʻahu's map: ${error.reason} ${error.message}")
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                AppLog.warn("map", "Saving Oʻahu's map: tile limit $limit reached")
            }
        })
        r.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    /**
     * The address OpenFreeMap serves tiles from right now (it carries the map data's date), read from
     * the style's vector source (TileJSON). Throws if it can't be read.
     */
    private fun currentTileAddress(): String {
        val style = JSONObject(get(MapTheme.STYLE_URL))
        val sources = style.getJSONObject("sources")
        for (key in sources.keys()) {
            val s = sources.getJSONObject(key)
            if (s.optString("type") != "vector") continue
            s.optJSONArray("tiles")?.optString(0)?.takeIf { it.isNotEmpty() }?.let { return it }
            val url = s.optString("url").takeIf { it.startsWith("http") } ?: continue
            val tileJson = JSONObject(get(url))
            tileJson.optJSONArray("tiles")?.optString(0)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        error("no vector tile address in the map style")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode} from $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun listRegions(manager: OfflineManager): Array<OfflineRegion> = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) = cont.resume(offlineRegions ?: emptyArray())
            override fun onError(error: String) = cont.resumeWith(Result.failure(IllegalStateException(error)))
        })
    }

    private suspend fun createRegion(manager: OfflineManager, tiles: String): OfflineRegion = suspendCancellableCoroutine { cont ->
        val def = OfflineTilePyramidRegionDefinition(MapTheme.STYLE_URL, OAHU, 0.0, MAX_ZOOM, PIXEL_RATIO)
        val meta = JSONObject().put("name", NAME).put("tiles", tiles).toString().toByteArray()
        manager.createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) = cont.resume(offlineRegion)
            override fun onError(error: String) = cont.resumeWith(Result.failure(IllegalStateException(error)))
        })
    }

    private suspend fun status(r: OfflineRegion): OfflineRegionStatus? = suspendCancellableCoroutine { cont ->
        r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) = cont.resume(status)
            override fun onError(error: String?) = cont.resume(null)
        })
    }

    private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)
}
