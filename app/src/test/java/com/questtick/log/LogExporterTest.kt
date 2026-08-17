package com.questtick.log

import com.questtick.data.LogEntry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class LogExporterTest {
    private val logs = listOf(
        LogEntry(1L, "DEBUG", "debug token=secret", "cookie_token=secret"),
        LogEntry(2L, "INFO", "info", ""),
        LogEntry(3L, "WARN", "warn", ""),
        LogEntry(4L, "ERROR", "error", ""),
    )

    @Test
    fun txtExportIsSanitized() {
        val text = LogExporter.buildText(logs, verbose = true)

        assertTrue(text.contains("[DEBUG]"))
        assertTrue(text.contains("[ERROR]"))
        assertFalse(text.contains("secret"))
    }

    @Test
    fun jsonExportIsValidAndSanitized() {
        val json = JSONObject(LogExporter.buildJson(logs, verbose = true))

        assertTrue(json.optJSONArray("logs")!!.length() == logs.size)
        assertFalse(json.toString().contains("secret"))
    }

    @Test
    fun zipExportContainsTxtAndJson() {
        val bytes = LogExporter.buildBytes(logs, verbose = true, format = LogExporter.ExportFormat.ZIP)
        val names = mutableSetOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
            }
        }

        assertTrue("signin_logs.txt" in names)
        assertTrue("signin_logs.json" in names)
        assertTrue("README.txt" in names)
    }
}
