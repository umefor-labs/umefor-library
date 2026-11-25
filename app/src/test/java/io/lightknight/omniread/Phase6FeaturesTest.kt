package io.lightknight.omniread

import io.lightknight.omniread.data.local.HighlightEntity
import io.lightknight.omniread.data.local.PronunciationRuleEntity
import io.lightknight.omniread.data.local.ReadingSessionEntity
import io.lightknight.omniread.data.repository.ReadingStatsCalculator
import io.lightknight.omniread.engine.ai.BookAiAssistant
import io.lightknight.omniread.engine.tts.TextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class Phase6FeaturesTest {
    @Test
    fun appliesPronunciationAndRegexSanitizerSafely() {
        val rules = listOf(
            PronunciationRuleEntity("1", "Heavy Object", "He-vi Óp-zếch", isRegex = false, enabled = true),
            PronunciationRuleEntity("2", """Đọc truyện tại .*$""", "", isRegex = true, enabled = true),
            PronunciationRuleEntity("3", "[invalid-regex", "noop", isRegex = true, enabled = true),
            PronunciationRuleEntity("4", "Quenser", "Kuen-sơ", isRegex = false, enabled = false)
        )
        val input = "Quenser nhìn về phía heavy object.\nĐọc truyện tại webabc.vn nhé!"
        val sanitized = TextSanitizer.applyRules(input, rules)
        assertTrue(sanitized.contains("He-vi Óp-zếch"))
        assertTrue(sanitized.contains("Quenser")) // disabled rule does not apply
        assertFalse(sanitized.contains("webabc.vn"))
    }

    @Test
    fun buildsMarkdownExportForBookNotes() {
        val highlights = listOf(
            HighlightEntity(
                id = "h1",
                itemId = "b1",
                chapterId = "c1",
                chapterTitle = "Chương 1: Khởi đầu",
                paragraphIndex = 4,
                quoteText = "Chiến tranh đã thay đổi.",
                colorHex = 0xFFFFEB3BL,
                note = "Câu mở đầu ấn tượng"
            )
        )
        val md = BookAiAssistant.buildNotesMarkdown("Heavy Object", "Kamachi Kazuma", highlights)
        assertTrue(md.contains("# Ghi chú & Highlights: Heavy Object"))
        assertTrue(md.contains("**Tác giả:** Kamachi Kazuma"))
        assertTrue(md.contains("## 1. Chương 1: Khởi đầu (Đoạn 5)"))
        assertTrue(md.contains("> Chiến tranh đã thay đổi."))
        assertTrue(md.contains("**Ghi chú:** Câu mở đầu ấn tượng"))
    }

    @Test
    fun calculatesReadingStreakAcrossDays() {
        val today = LocalDate.of(2026, 11, 25)
        val sessions = listOf(
            ReadingSessionEntity("2026-11-25", readingSeconds = 300),
            ReadingSessionEntity("2026-11-24", listeningSeconds = 600),
            ReadingSessionEntity("2026-11-23", chaptersCompleted = 1),
            ReadingSessionEntity("2026-11-21", readingSeconds = 900) // gap on 22nd stops streak
        )
        assertEquals(3, ReadingStatsCalculator.calculateStreak(sessions, today))
        assertEquals(0, ReadingStatsCalculator.calculateStreak(emptyList(), today))
    }
}
