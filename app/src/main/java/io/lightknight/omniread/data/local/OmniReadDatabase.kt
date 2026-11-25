package io.lightknight.omniread.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LibraryItemEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        CharacterVoiceEntity::class,
        ChapterScriptEntity::class,
        SourcePluginEntity::class,
        ExtensionRepoEntity::class,
        SyncJobEntity::class,
        HighlightEntity::class,
        ReadingSessionEntity::class,
        PronunciationRuleEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class OmniReadDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun chapterDao(): ChapterDao
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun voiceCastDao(): VoiceCastDao
    abstract fun chapterScriptDao(): ChapterScriptDao
    abstract fun sourcePluginDao(): SourcePluginDao
    abstract fun extensionRepoDao(): ExtensionRepoDao
    abstract fun syncJobDao(): SyncJobDao
    abstract fun highlightDao(): HighlightDao
    abstract fun readingStatsDao(): ReadingStatsDao
    abstract fun pronunciationRuleDao(): PronunciationRuleDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE library_items ADD COLUMN shelfTag TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE library_items ADD COLUMN unreadNewChapters INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE library_items ADD COLUMN trackUpdates INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `highlights` (
                        `id` TEXT NOT NULL,
                        `itemId` TEXT NOT NULL,
                        `chapterId` TEXT NOT NULL,
                        `chapterTitle` TEXT NOT NULL,
                        `paragraphIndex` INTEGER NOT NULL,
                        `quoteText` TEXT NOT NULL,
                        `colorHex` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`itemId`) REFERENCES `library_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_itemId` ON `highlights` (`itemId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reading_sessions` (
                        `dateKey` TEXT NOT NULL,
                        `readingSeconds` INTEGER NOT NULL,
                        `listeningSeconds` INTEGER NOT NULL,
                        `chaptersCompleted` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`dateKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pronunciation_rules` (
                        `id` TEXT NOT NULL,
                        `pattern` TEXT NOT NULL,
                        `replacement` TEXT NOT NULL,
                        `isRegex` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE source_plugins ADD COLUMN repoId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE source_plugins ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE source_plugins ADD COLUMN iconUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE source_plugins ADD COLUMN scriptSha256 TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `extension_repos` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `indexUrl` TEXT NOT NULL,
                        `mirrorIndexUrl` TEXT NOT NULL,
                        `websiteUrl` TEXT NOT NULL,
                        `issueUrl` TEXT NOT NULL,
                        `isOfficial` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `lastSyncedAt` INTEGER NOT NULL,
                        `cachedIndexJson` TEXT NOT NULL,
                        `lastError` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun build(context: Context): OmniReadDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                OmniReadDatabase::class.java,
                "omniread.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration(false)
                .build()
        }
    }
}
