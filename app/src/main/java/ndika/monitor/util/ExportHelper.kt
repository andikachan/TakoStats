package ndika.monitor.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import ndika.monitor.data.SessionRecord
import ndika.monitor.storage.StorageManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExportHelper {

    fun exportRecordToZip(context: Context, record: SessionRecord): File? {
        return try {
            val exportDir = StorageManager.getExportDirectory(context)
            val cleanAppName = record.appName.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(record.startTimeMs))
            val zipFile = File(exportDir, "${cleanAppName}_benchmark_${dateStr}.zip")

            val frametimeCsv = generateFrametimeCsv(record)
            val telemetryCsv = generateTelemetryCsv(record)

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // 1. Add Frametimes CSV
                val frameEntry = ZipEntry("frametime_${record.id}.csv")
                zos.putNextEntry(frameEntry)
                zos.write(frametimeCsv.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 2. Add Telemetry CSV
                val teleEntry = ZipEntry("telemetry_${record.id}.csv")
                zos.putNextEntry(teleEntry)
                zos.write(telemetryCsv.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            zipFile
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to cache directory if external storage fails
            try {
                val fallbackDir = File(context.cacheDir, "exports").apply { mkdirs() }
                val cleanAppName = record.appName.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(record.startTimeMs))
                val zipFile = File(fallbackDir, "${cleanAppName}_benchmark_${dateStr}.zip")

                val frametimeCsv = generateFrametimeCsv(record)
                val telemetryCsv = generateTelemetryCsv(record)

                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    val frameEntry = ZipEntry("frametime_${record.id}.csv")
                    zos.putNextEntry(frameEntry)
                    zos.write(frametimeCsv.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    val teleEntry = ZipEntry("telemetry_${record.id}.csv")
                    zos.putNextEntry(teleEntry)
                    zos.write(telemetryCsv.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                zipFile
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun generateFrametimeCsv(record: SessionRecord): String {
        val sb = StringBuilder()
        sb.append("FrameIndex,FrameTimeMs,InstantFps\n")
        val frameTimes = record.parseFrameTimes()
        for (i in frameTimes.indices) {
            val ft = frameTimes[i]
            val fps = if (ft > 0f) 1000f / ft else 0f
            sb.append(i).append(",")
            sb.append(String.format(Locale.US, "%.2f", ft)).append(",")
            sb.append(String.format(Locale.US, "%.1f", fps)).append("\n")
        }
        return sb.toString()
    }

    private fun generateTelemetryCsv(record: SessionRecord): String {
        val sb = StringBuilder()
        sb.append("TimestampMs,FPS,CpuUsagePct,CpuFreqGhz,CpuTempC,GpuUsagePct,GpuTempC,BatTempC,SkinTempC,VoltageV,CurrentA,PowerW,FramePowerMj,MemoryMb\n")
        val samples = record.parseTelemetrySamples()
        for (s in samples) {
            sb.append(s.timestampMs).append(",")
            sb.append(String.format(Locale.US, "%.1f", s.fps)).append(",")
            sb.append(String.format(Locale.US, "%.1f", s.cpuUsage)).append(",")
            sb.append(String.format(Locale.US, "%.2f", s.cpuFreqGhz)).append(",")
            sb.append(String.format(Locale.US, "%.1f", s.cpuTemp)).append(",")
            sb.append(String.format(Locale.US, "%.1f", s.gpuUsage)).append(",")
            sb.append(String.format(Locale.US, "%.1f", s.gpuTemp)).append(",")
            sb.append(String.format(Locale.US, "%.1f", s.batTemp)).append(",")
            sb.append(String.format(Locale.US, "%.1f", s.skinTemp)).append(",")
            sb.append(String.format(Locale.US, "%.2f", s.voltageV)).append(",")
            sb.append(String.format(Locale.US, "%.2f", s.currentA)).append(",")
            sb.append(String.format(Locale.US, "%.2f", s.powerW)).append(",")
            sb.append(String.format(Locale.US, "%.2f", s.framePowerMj)).append(",")
            sb.append(s.memMb).append("\n")
        }
        return sb.toString()
    }

    fun shareRecordZip(context: Context, record: SessionRecord) {
        val zipFile = exportRecordToZip(context, record) ?: return
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "${record.appName} Benchmark Record")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share Benchmark ZIP"))
    }
}
