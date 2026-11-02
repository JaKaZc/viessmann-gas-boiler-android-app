package com.vigasboiler.app.discovery

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Zips the debug dump into [exportDir], optionally passing every file through [DumpRedactor]. */
class DumpExporter(private val dumpDir: File, private val exportDir: File) {

    suspend fun export(redact: Boolean): File = withContext(Dispatchers.IO) {
        val files = dumpDir.listFiles { f -> f.isFile }?.sortedBy { it.name }.orEmpty()
        check(files.isNotEmpty()) { "No dump to export. Run discovery first." }

        val contents = files.associate { it.name to it.readText() }
        val output = if (redact) {
            val redactor = DumpRedactor()
            contents.forEach { (name, text) -> redactor.collect(text, isPersonalDocument(name)) }
            contents.mapValues { (name, text) -> redactor.redact(text, isPersonalDocument(name)) }
        } else {
            contents
        }

        exportDir.listFiles()?.forEach { it.delete() } // keep only the latest export around
        exportDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val zip = File(exportDir, "viessmann-dump-${if (redact) "redacted" else "raw"}-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            output.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.encodeToByteArray())
                out.closeEntry()
            }
        }
        zip
    }

    /** The users/me response is nothing but profile data; it is masked as a whole. */
    private fun isPersonalDocument(fileName: String) = fileName.startsWith("01_users_me")
}
