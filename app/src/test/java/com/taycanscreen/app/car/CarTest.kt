package com.taycanscreen.app.car

import com.taycanscreen.app.nav.LatLon
import com.taycanscreen.app.nav.LocalDirections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarTest {
    @Test fun maxspeedTags() {
        assertEquals(35, SpeedLimit.parseMaxspeed("35 mph"))
        assertEquals(31, SpeedLimit.parseMaxspeed("50"))
        assertEquals(25, SpeedLimit.parseMaxspeed("25 mph;35 mph"))
        assertNull(SpeedLimit.parseMaxspeed("none"))
        assertNull(SpeedLimit.parseMaxspeed("signals"))
    }

    @Test fun nearestRoadAlongHeading() {
        // Two roads 10 m from the car: one running east–west, one north–south. Heading east picks the first.
        val json = """
            {"elements":[
              {"type":"way","id":1,"tags":{"highway":"primary","maxspeed":"35 mph","name":"Kapiolani Boulevard"},
               "geometry":[{"lat":21.29009,"lon":-157.8400},{"lat":21.29009,"lon":-157.8300}]},
              {"type":"way","id":2,"tags":{"highway":"residential","maxspeed":"25 mph","name":"Piikoi Street"},
               "geometry":[{"lat":21.2850,"lon":-157.83490},{"lat":21.2950,"lon":-157.83490}]}
            ]}
        """.trimIndent()
        val car = LatLon(21.2900, -157.8350)
        assertEquals(35 to "Kapiolani Boulevard", SpeedLimit.pick(json, car, 90f))
        assertEquals(25 to "Piikoi Street", SpeedLimit.pick(json, car, 0f))
    }

    @Test fun arrivalBattery() {
        // 60 % with 150 mi range, 30 mi left → 48 %.
        assertEquals(48.0, Arrival.pct(30 * 1609.344, 60.0, 150.0)!!, 0.01)
        assertNull(Arrival.pct(1000.0, null, 150.0))
    }

    @Test fun localDirections() {
        val honolulu = LatLon(21.2950, -157.8400)
        val m = LocalDirections.Mode.Local
        assertEquals("MAUKA", LocalDirections.name(20.0, honolulu, m))
        assertEquals("DIAMOND HEAD", LocalDirections.name(110.0, honolulu, m))
        assertEquals("MAKAI", LocalDirections.name(205.0, honolulu, m))
        assertEquals("ʻEWA", LocalDirections.name(290.0, honolulu, m))
        assertEquals("EAST", LocalDirections.name(90.0, honolulu, LocalDirections.Mode.Compass))
        assertEquals("NORTH", LocalDirections.name(10.0, LatLon(37.0, -122.0), m)) // off Oʻahu
    }
}
