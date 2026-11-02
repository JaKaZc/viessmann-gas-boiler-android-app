package com.vigasboiler.app.feature

import java.io.File

/** Reads the redacted real dump in `docs/feature-dump/`. Unit tests run with the module as working dir. */
object Fixtures {
    private val dir = listOf(File("../docs/feature-dump"), File("docs/feature-dump")).first { it.isDirectory }

    fun read(name: String): String = File(dir, name).readText()

    val boilerFeatures: List<Feature> by lazy { FeatureParser.parse(read("07_device_features_i0_g0_d0.json")) }
    val gatewayFeatures: List<Feature> by lazy { FeatureParser.parse(read("06_gateway_features_i0_g0.json")) }

    fun boiler(name: String): Feature = boilerFeatures.first { it.name == name }
    fun command(feature: String, command: String): Command = boiler(feature).commands.getValue(command)
}
