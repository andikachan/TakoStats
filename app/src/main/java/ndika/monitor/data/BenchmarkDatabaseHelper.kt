package ndika.monitor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class BenchmarkDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_RECORDS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_APP_NAME TEXT,
                $COL_PACKAGE_NAME TEXT,
                $COL_START_TIME INTEGER,
                $COL_END_TIME INTEGER,
                $COL_DURATION INTEGER,
                $COL_AVG_FPS REAL,
                $COL_P95_FPS REAL,
                $COL_P99_FPS REAL,
                $COL_MAX_FRAMETIME REAL,
                $COL_AVG_CPU REAL,
                $COL_AVG_CPU_TEMP REAL,
                $COL_AVG_GPU REAL,
                $COL_AVG_POWER REAL,
                $COL_AVG_FRAME_POWER REAL,
                $COL_TOTAL_FRAMES INTEGER,
                $COL_FRAMETIMES_CSV TEXT,
                $COL_TELEMETRY_JSON TEXT
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_RECORDS")
        onCreate(db)
    }

    fun insertRecord(record: SessionRecord): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_APP_NAME, record.appName)
            put(COL_PACKAGE_NAME, record.packageName)
            put(COL_START_TIME, record.startTimeMs)
            put(COL_END_TIME, record.endTimeMs)
            put(COL_DURATION, record.durationMs)
            put(COL_AVG_FPS, record.avgFps)
            put(COL_P95_FPS, record.p95Fps)
            put(COL_P99_FPS, record.p99Fps)
            put(COL_MAX_FRAMETIME, record.maxFrameTimeMs)
            put(COL_AVG_CPU, record.avgCpuUsage)
            put(COL_AVG_CPU_TEMP, record.avgCpuTemp)
            put(COL_AVG_GPU, record.avgGpuUsage)
            put(COL_AVG_POWER, record.avgPowerWatts)
            put(COL_AVG_FRAME_POWER, record.avgFramePowerMj)
            put(COL_TOTAL_FRAMES, record.totalFrames)
            put(COL_FRAMETIMES_CSV, record.frameTimesCsv)
            put(COL_TELEMETRY_JSON, record.telemetryJson)
        }
        val id = db.insert(TABLE_RECORDS, null, values)
        record.id = id
        return id
    }

    fun getAllRecords(): List<SessionRecord> {
        val list = mutableListOf<SessionRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_RECORDS,
            null,
            null,
            null,
            null,
            null,
            "$COL_START_TIME DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                val record = SessionRecord(
                    id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                    appName = it.getString(it.getColumnIndexOrThrow(COL_APP_NAME)) ?: "",
                    packageName = it.getString(it.getColumnIndexOrThrow(COL_PACKAGE_NAME)) ?: "",
                    startTimeMs = it.getLong(it.getColumnIndexOrThrow(COL_START_TIME)),
                    endTimeMs = it.getLong(it.getColumnIndexOrThrow(COL_END_TIME)),
                    durationMs = it.getLong(it.getColumnIndexOrThrow(COL_DURATION)),
                    avgFps = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_FPS)),
                    p95Fps = it.getFloat(it.getColumnIndexOrThrow(COL_P95_FPS)),
                    p99Fps = it.getFloat(it.getColumnIndexOrThrow(COL_P99_FPS)),
                    maxFrameTimeMs = it.getFloat(it.getColumnIndexOrThrow(COL_MAX_FRAMETIME)),
                    avgCpuUsage = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_CPU)),
                    avgCpuTemp = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_CPU_TEMP)),
                    avgGpuUsage = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_GPU)),
                    avgPowerWatts = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_POWER)),
                    avgFramePowerMj = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_FRAME_POWER)),
                    totalFrames = it.getInt(it.getColumnIndexOrThrow(COL_TOTAL_FRAMES)),
                    frameTimesCsv = it.getString(it.getColumnIndexOrThrow(COL_FRAMETIMES_CSV)) ?: "",
                    telemetryJson = it.getString(it.getColumnIndexOrThrow(COL_TELEMETRY_JSON)) ?: ""
                )
                list.add(record)
            }
        }
        return list
    }

    fun getRecordById(id: Long): SessionRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_RECORDS,
            null,
            "$COL_ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )

        cursor.use {
            if (it.moveToFirst()) {
                return SessionRecord(
                    id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                    appName = it.getString(it.getColumnIndexOrThrow(COL_APP_NAME)) ?: "",
                    packageName = it.getString(it.getColumnIndexOrThrow(COL_PACKAGE_NAME)) ?: "",
                    startTimeMs = it.getLong(it.getColumnIndexOrThrow(COL_START_TIME)),
                    endTimeMs = it.getLong(it.getColumnIndexOrThrow(COL_END_TIME)),
                    durationMs = it.getLong(it.getColumnIndexOrThrow(COL_DURATION)),
                    avgFps = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_FPS)),
                    p95Fps = it.getFloat(it.getColumnIndexOrThrow(COL_P95_FPS)),
                    p99Fps = it.getFloat(it.getColumnIndexOrThrow(COL_P99_FPS)),
                    maxFrameTimeMs = it.getFloat(it.getColumnIndexOrThrow(COL_MAX_FRAMETIME)),
                    avgCpuUsage = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_CPU)),
                    avgCpuTemp = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_CPU_TEMP)),
                    avgGpuUsage = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_GPU)),
                    avgPowerWatts = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_POWER)),
                    avgFramePowerMj = it.getFloat(it.getColumnIndexOrThrow(COL_AVG_FRAME_POWER)),
                    totalFrames = it.getInt(it.getColumnIndexOrThrow(COL_TOTAL_FRAMES)),
                    frameTimesCsv = it.getString(it.getColumnIndexOrThrow(COL_FRAMETIMES_CSV)) ?: "",
                    telemetryJson = it.getString(it.getColumnIndexOrThrow(COL_TELEMETRY_JSON)) ?: ""
                )
            }
        }
        return null
    }

    fun deleteRecord(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_RECORDS, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun deleteAllRecords(): Int {
        val db = writableDatabase
        return db.delete(TABLE_RECORDS, null, null)
    }

    companion object {
        private const val DATABASE_NAME = "ndimonitor_benchmarks.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_RECORDS = "benchmark_records"
        const val COL_ID = "id"
        const val COL_APP_NAME = "app_name"
        const val COL_PACKAGE_NAME = "package_name"
        const val COL_START_TIME = "start_time"
        const val COL_END_TIME = "end_time"
        const val COL_DURATION = "duration"
        const val COL_AVG_FPS = "avg_fps"
        const val COL_P95_FPS = "p95_fps"
        const val COL_P99_FPS = "p99_fps"
        const val COL_MAX_FRAMETIME = "max_frametime"
        const val COL_AVG_CPU = "avg_cpu"
        const val COL_AVG_CPU_TEMP = "avg_cpu_temp"
        const val COL_AVG_GPU = "avg_gpu"
        const val COL_AVG_POWER = "avg_power"
        const val COL_AVG_FRAME_POWER = "avg_frame_power"
        const val COL_TOTAL_FRAMES = "total_frames"
        const val COL_FRAMETIMES_CSV = "frametimes_csv"
        const val COL_TELEMETRY_JSON = "telemetry_json"

        @Volatile
        private var instance: BenchmarkDatabaseHelper? = null

        fun getInstance(context: Context): BenchmarkDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: BenchmarkDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
