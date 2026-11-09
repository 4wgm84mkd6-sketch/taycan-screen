package com.taycanscreen.app.map

import com.taycanscreen.app.log.AppLog
import com.taycanscreen.app.ui.DriveColors
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * Vector map (MapLibre + OpenFreeMap, free and key-less, OpenStreetMap data), recoloured to the v3
 * boards at runtime: every layer of OpenFreeMap's "positron" style gets a v3 colour by what it is
 * (water, park, building, road, label…). Text is drawn by the box itself, so it stays sharp at any size.
 * Satellite: Esri World Imagery as a raster layer under the vector labels (which turn white).
 */
object MapTheme {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
    const val ATTRIBUTION = "OpenFreeMap © OpenMapTiles · Data © OpenStreetMap contributors"
    const val SATELLITE_ATTRIBUTION = "Imagery © Esri, Maxar, Earthstar Geographics · Labels © OpenStreetMap contributors"
    private const val SAT_SOURCE = "esri-imagery"
    private const val SAT_LAYER = "esri-imagery"
    private const val ESRI_URL = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    private class Palette(
        val land: Int, val landuse: Int, val park: Int, val water: Int, val building: Int,
        val roadMinor: Int, val roadMid: Int, val roadMajor: Int, val casing: Int, val rail: Int, val boundary: Int,
        val label: Int, val labelMajor: Int, val halo: Int,
    )

    // From the v3 boards' map (Drive — night / Drive — day).
    private val NIGHT = Palette(
        land = 0xFF131517.toInt(), landuse = 0xFF15171A.toInt(), park = 0xFF12201A.toInt(), water = 0xFF0B1A22.toInt(),
        building = 0xFF17191B.toInt(), roadMinor = 0xFF1C1F22.toInt(), roadMid = 0xFF24272B.toInt(), roadMajor = 0xFF2A2E33.toInt(),
        casing = 0xFF131517.toInt(), rail = 0xFF1C1F22.toInt(), boundary = 0xFF26292C.toInt(),
        label = 0xFF7E8389.toInt(), labelMajor = 0xFFA2A6AB.toInt(), halo = 0xFF0E0F10.toInt(),
    )
    private val DAY = Palette(
        land = 0xFFF1EFEB.toInt(), landuse = 0xFFECEAE5.toInt(), park = 0xFFD7E3D4.toInt(), water = 0xFFCFDDE3.toInt(),
        building = 0xFFE8E6E1.toInt(), roadMinor = 0xFFFBFAF8.toInt(), roadMid = 0xFFFFFFFF.toInt(), roadMajor = 0xFFFFFFFF.toInt(),
        casing = 0xFFDEDCD7.toInt(), rail = 0xFFDEDCD7.toInt(), boundary = 0xFFC4C1BB.toInt(),
        label = 0xFF6A6D71.toInt(), labelMajor = 0xFF55585C.toInt(), halo = 0xFFF4F3F0.toInt(),
    )

    private fun String.has(vararg words: String) = words.any { contains(it) }

    /** Is this one of our own layers (route, satellite)? Those keep their own colours. */
    private fun ours(id: String) = id.startsWith("route-") || id.startsWith("dest-") || id == SAT_LAYER

    fun apply(style: Style, c: DriveColors, satellite: Boolean) {
        val p = if (c.isNight) NIGHT else DAY
        ensureSatellite(style)
        var n = 0
        for (layer in style.layers) {
            val id = layer.id.lowercase()
            if (ours(id)) continue
            n++
            try {
                when (layer) {
                    is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(p.land))
                    is FillLayer -> {
                        val col = when {
                            id.has("water", "ocean", "lake", "river") -> p.water
                            id.has("park", "wood", "forest", "grass", "landcover", "golf", "cemetery", "garden", "pitch") -> p.park
                            id.has("building") -> p.building
                            id.has("aeroway", "landuse", "residential", "industrial", "commercial", "school", "hospital") -> p.landuse
                            else -> p.landuse
                        }
                        layer.setProperties(
                            PropertyFactory.fillColor(col), PropertyFactory.fillOutlineColor(col),
                            PropertyFactory.visibility(if (satellite) Property.NONE else Property.VISIBLE),
                        )
                    }
                    is FillExtrusionLayer -> layer.setProperties(
                        PropertyFactory.fillExtrusionColor(p.building),
                        PropertyFactory.visibility(if (satellite) Property.NONE else Property.VISIBLE),
                    )
                    is LineLayer -> {
                        val col = when {
                            id.has("water", "river", "stream", "canal") -> p.water
                            id.has("boundary", "admin") -> p.boundary
                            id.has("rail", "transit") -> p.rail
                            id.has("casing", "outline") -> p.casing
                            id.has("motorway", "trunk", "primary") -> p.roadMajor
                            id.has("secondary", "tertiary") -> p.roadMid
                            else -> p.roadMinor
                        }
                        layer.setProperties(
                            PropertyFactory.lineColor(col),
                            PropertyFactory.visibility(if (satellite) Property.NONE else Property.VISIBLE),
                        )
                    }
                    is SymbolLayer -> {
                        val major = id.has("motorway", "trunk", "primary", "city", "town", "state", "country", "place")
                        layer.setProperties(
                            PropertyFactory.textColor(if (satellite) 0xFFFFFFFF.toInt() else if (major) p.labelMajor else p.label),
                            PropertyFactory.textHaloColor(if (satellite) 0xCC000000.toInt() else p.halo),
                            PropertyFactory.textHaloWidth(if (satellite) 1.6f else 1.2f),
                        )
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                AppLog.warn("map", "Couldn't recolour layer ${layer.id}: ${e.message}")
            }
        }
        style.getLayer(SAT_LAYER)?.setProperties(
            PropertyFactory.visibility(if (satellite) Property.VISIBLE else Property.NONE),
            PropertyFactory.rasterBrightnessMax(if (c.isNight) 0.6f else 1f),
        )
        AppLog.info("map", "Map style: ${if (satellite) "satellite" else if (c.isNight) "night" else "day"} ($n layers recoloured)")
    }

    /** Id of the first label layer: our raster and route layers go below it, so names stay on top. */
    fun firstSymbolLayerId(style: Style): String? = style.layers.firstOrNull { it is SymbolLayer }?.id

    private fun ensureSatellite(style: Style) {
        if (style.getSource(SAT_SOURCE) == null) {
            val tiles = TileSet("2.2.0", ESRI_URL).apply { setMaxZoom(19f) }
            style.addSource(RasterSource(SAT_SOURCE, tiles, 256))
        }
        if (style.getLayer(SAT_LAYER) == null) {
            val layer: Layer = RasterLayer(SAT_LAYER, SAT_SOURCE).withProperties(PropertyFactory.visibility(Property.NONE))
            val below = firstSymbolLayerId(style)
            if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
        }
    }
}
