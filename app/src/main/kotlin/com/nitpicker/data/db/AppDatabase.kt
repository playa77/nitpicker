package com.nitpicker.data.db

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities =
        [
            SessionRow::class,
            AudioChunkRow::class,
            StatementRow::class,
            AnnotationRow::class,
            ClaimRow::class,
            SpeakerMappingRow::class,
            AnalysisJobRow::class,
        ],
    views = [SegmentationPending::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DbConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    abstract fun chunks(): ChunkDao

    abstract fun statements(): StatementDao

    abstract fun annotations(): AnnotationDao

    abstract fun claims(): ClaimDao

    abstract fun mappings(): SpeakerMappingDao

    abstract fun jobs(): AnalysisJobDao

    companion object {
        fun builder(context: Context, name: String = "app_database"): Builder<AppDatabase> =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addCallback(CONSTRAINTS)

        fun inMemoryBuilder(context: Context): Builder<AppDatabase> =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                .addCallback(CONSTRAINTS)

        // Room annotations cannot express CHECK. Rebuild empty tables in dependency order,
        // retaining the generated SQL and indices, and enforce the approved enum domains.
        val CONSTRAINTS =
            object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val tables =
                        listOf(
                            "sessions",
                            "audio_chunks",
                            "statements",
                            "annotations",
                            "claims",
                            "speaker_mappings",
                            "analysis_jobs",
                        )
                    val checks =
                        mapOf(
                            "sessions" to
                                "language IN ('DE','EN') AND status IN ('ACTIVE','STOPPING','COMPLETED','FAILED')",
                            "audio_chunks" to
                                "state IN ('CAPTURED','ASR_PENDING','ASR_DONE','ANALYZED','DISCARDED','FAILED')",
                            "statements" to "state IN ('PROVISIONAL','FINAL')",
                            "annotations" to "family IN ('FACT_CHECK','LOGIC_RHETORIC')",
                            "analysis_jobs" to
                                "reason IN ('CHUNK','REANALYSIS') AND state IN ('PENDING','IN_FLIGHT','DONE','FAILED')",
                        )
                    val definitions = tables.associateWith { table ->
                        db.query(
                                "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
                                arrayOf(table),
                            )
                            .use { c ->
                                check(c.moveToFirst())
                                c.getString(0)
                            }
                    }
                    val indices = mutableListOf<String>()
                    db.query(
                            "SELECT sql FROM sqlite_master WHERE type='index' AND sql IS NOT NULL AND tbl_name IN (${tables.joinToString(",") { "'${it}'" }})"
                        )
                        .use { c -> while (c.moveToNext()) indices.add(c.getString(0)) }
                    db.execSQL("DROP VIEW IF EXISTS segmentation_pending")
                    tables.reversed().forEach { db.execSQL("DROP TABLE `$it`") }
                    tables.forEach { table ->
                        val original = definitions.getValue(table)
                        val condition = checks[table]
                        db.execSQL(
                            if (condition == null) original
                            else original.substringBeforeLast(")") + ", CHECK ($condition))"
                        )
                    }
                    indices.forEach(db::execSQL)
                    db.execSQL(
                        "CREATE VIEW segmentation_pending AS SELECT c.* FROM audio_chunks c WHERE c.state='ASR_DONE' AND NOT EXISTS (SELECT 1 FROM analysis_jobs j WHERE j.session_id=c.session_id AND j.reason='CHUNK' AND j.chunk_seq=c.seq)"
                    )
                }
            }
    }
}
