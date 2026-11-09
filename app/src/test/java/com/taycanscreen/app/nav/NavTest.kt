package com.taycanscreen.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing follows the documented response formats of Photon (GeoJSON) and OSRM v1 route. */
class NavTest {
    private val photon = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-157.8434,21.2911]},
           "properties":{"osm_type":"W","osm_id":1,"name":"Ala Moana Center","street":"Ala Moana Boulevard","housenumber":"1450","city":"Honolulu","state":"Hawaii","country":"United States","osm_key":"shop","osm_value":"mall"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-157.81,21.29]},
           "properties":{"street":"Kapiolani Boulevard","housenumber":"2250","city":"Honolulu","state":"Hawaii"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-157.8434,21.2911]},
           "properties":{"name":"Ala Moana Center","city":"Honolulu"}}
        ]}
    """.trimIndent()

    @Test fun photonPlaces() {
        val p = NavApi.parsePhoton(photon)
        assertEquals(2, p.size) // duplicate at the same spot dropped
        assertEquals("Ala Moana Center", p[0].name)
        assertEquals("1450 Ala Moana Boulevard, Honolulu, Hawaii", p[0].detail)
        assertEquals(21.2911, p[0].lat, 1e-9)
        assertEquals(-157.8434, p[0].lon, 1e-9)
        assertEquals("2250 Kapiolani Boulevard", p[1].name)
        assertEquals("Honolulu, Hawaii", p[1].detail)
    }

    private val osrm = """
        {"code":"Ok","waypoints":[],"routes":[{"distance":3125.4,"duration":402.1,"weight":402.1,
          "geometry":{"type":"LineString","coordinates":[[-157.8171,21.2969],[-157.8200,21.2969],[-157.8200,21.2930],[-157.8430,21.2911]]},
          "legs":[{"distance":3125.4,"duration":402.1,"summary":"","steps":[
            {"distance":300.0,"duration":40,"name":"Kalakaua Avenue","mode":"driving","maneuver":{"location":[-157.8171,21.2969],"type":"depart","bearing_after":270}},
            {"distance":430.0,"duration":60,"name":"Kapiolani Boulevard","ref":"HI 92","mode":"driving","maneuver":{"location":[-157.8200,21.2969],"type":"turn","modifier":"left"}},
            {"distance":2395.4,"duration":300,"name":"","mode":"driving","maneuver":{"location":[-157.8200,21.2930],"type":"roundabout","modifier":"right","exit":2}},
            {"distance":0,"duration":0,"name":"","mode":"driving","maneuver":{"location":[-157.8430,21.2911],"type":"arrive"}}
          ]}]}]}
    """.trimIndent()

    @Test fun osrmRoute() {
        val r = NavApi.parseOsrm(osrm)
        assertEquals(4, r.points.size)
        assertEquals(3125.4, r.distanceM, 1e-6)
        assertEquals(402.1, r.durationS, 1e-6)
        assertEquals(4, r.steps.size)
        assertEquals("left", r.steps[1].modifier)
        assertEquals(2, r.steps[2].exit)
        assertNull(r.steps[0].modifier)
        assertEquals("Turn left onto Kapiolani Boulevard", RouteMath.instruction(r.steps[1]))
        assertEquals("At the roundabout, take the 2nd exit", RouteMath.instruction(r.steps[2]))
        assertEquals("Arrive at your destination", RouteMath.instruction(r.steps[3]))
    }

    @Test(expected = java.io.IOException::class)
    fun osrmNoRoute() {
        NavApi.parseOsrm("""{"code":"NoRoute","message":"Impossible route between points"}""")
    }

    @Test fun projectionAlongRoute() {
        val pts = listOf(LatLon(21.0, -157.0), LatLon(21.0, -156.99), LatLon(20.99, -156.99))
        val cum = RouteMath.cumulative(pts)
        assertEquals(1038.9, cum[1], 2.0) // 0.01° of longitude at 21°N
        // 20 m north of the middle of the first leg.
        val p = RouteMath.project(pts, cum, LatLon(21.0 + 20 / 111_320.0, -156.995))
        assertEquals(0, p.seg)
        assertEquals(cum[1] / 2, p.along, 2.0)
        assertEquals(20.0, p.offM, 0.5)
    }

    @Test fun distances() {
        assertEquals("500 ft", RouteMath.formatDistance(150.0))
        assertEquals("0.3 mi", RouteMath.formatDistance(482.8))
        assertEquals("12 mi", RouteMath.formatDistance(19312.0))
        assertTrue(RouteMath.formatDistance(10.0).endsWith("ft"))
    }
}
