package com.vigasboiler.app.discovery

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Developer tool, skipped unless both properties are set. Redacts a raw dump copied off the phone
 * with the exact code path of the in-app export, without touching the phone:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*ExportLocalDumpTest' -PrawDumpDir=/abs/raw -PredactedOutDir=/abs/out
 */
class ExportLocalDumpTest {

    @Test
    fun exportRedactedDump() = runTest {
        val raw = System.getProperty("rawDumpDir").orEmpty()
        val out = System.getProperty("redactedOutDir").orEmpty()
        assumeTrue("rawDumpDir / redactedOutDir not set", raw.isNotBlank() && out.isNotBlank())

        val exportDir = Files.createTempDirectory("export").toFile()
        val zip = DumpExporter(File(raw), exportDir).export(redact = true)
        val outDir = File(out).apply { mkdirs() }
        ZipInputStream(zip.inputStream()).use { input ->
            generateSequence { input.nextEntry }.forEach { entry ->
                File(outDir, File(entry.name).name).writeBytes(input.readBytes())
            }
        }
        exportDir.deleteRecursively()
    }
}
