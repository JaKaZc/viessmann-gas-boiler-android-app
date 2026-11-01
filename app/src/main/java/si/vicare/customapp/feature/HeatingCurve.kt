package si.vicare.customapp.feature

/**
 * Viessmann heating curve: supply temperature as a function of outside temperature.
 *
 *     dT = outside − room
 *     supply = room + shift − slope · dT · (1.4347 + 0.021 · dT + 247.9·10⁻⁶ · dT²)
 *
 * This is the formula commonly used to reproduce the Vitotronic/E3 curve charts. The boiler
 * additionally clamps the result to the circuit's supply limits.
 */
object HeatingCurve {
    fun supplyTemperature(slope: Double, shift: Double, roomSetpoint: Double, outside: Double): Double {
        val dT = outside - roomSetpoint
        return roomSetpoint + shift - slope * dT * (1.4347 + 0.021 * dT + 247.9e-6 * dT * dT)
    }

    /** Points for a chart from [fromOutside] to [toOutside], clamped to [min]..[max] when given. */
    fun points(
        slope: Double,
        shift: Double,
        roomSetpoint: Double,
        fromOutside: Int = -20,
        toOutside: Int = 20,
        min: Double? = null,
        max: Double? = null,
    ): List<Pair<Double, Double>> = (fromOutside..toOutside).map { t ->
        val raw = supplyTemperature(slope, shift, roomSetpoint, t.toDouble())
        t.toDouble() to raw.coerceIn(min ?: Double.NEGATIVE_INFINITY, max ?: Double.POSITIVE_INFINITY)
    }
}
