package com.taycanscreen.app.car

/**
 * Battery on arrival: today's charge minus the share of the car's own range estimate the rest of the
 * route uses. The car's range already reflects recent driving, temperature and climate use.
 */
object Arrival {
    fun pct(remainingM: Double, socPct: Double?, rangeMi: Double?): Double? {
        if (socPct == null || rangeMi == null || rangeMi < 1.0) return null
        val miles = remainingM / 1609.344
        return socPct * (1.0 - miles / rangeMi)
    }
}
