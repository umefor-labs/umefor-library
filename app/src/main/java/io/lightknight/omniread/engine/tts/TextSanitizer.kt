package io.lightknight.omniread.engine.tts

import io.lightknight.omniread.data.local.PronunciationRuleEntity
import java.util.UUID

object TextSanitizer {
    fun applyRules(text: String, rules: List<PronunciationRuleEntity>): String {
        if (text.isBlank() || rules.isEmpty()) return text
        var current = text
        for (rule in rules) {
            if (!rule.enabled || rule.pattern.isBlank()) continue
            current = if (rule.isRegex) {
                runCatching {
                    Regex(rule.pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
                        .replace(current, rule.replacement)
                }.getOrDefault(current)
            } else {
                replaceCaseInsensitive(current, rule.pattern, rule.replacement)
            }
        }
        return current.replace(Regex("[ \t]{2,}"), " ").trim()
    }

    private fun replaceCaseInsensitive(source: String, target: String, replacement: String): String {
        val escaped = Regex.escape(target)
        return Regex(escaped, RegexOption.IGNORE_CASE).replace(source, Regex.escapeReplacement(replacement))
    }

    fun defaultVietnameseWebFilterRules(): List<PronunciationRuleEntity> = listOf(
        PronunciationRuleEntity(
            id = UUID.randomUUID().toString(),
            pattern = """(Đọc truyện|Xem thêm|Nguồn truyện|Convert by|Sưu tầm|Mời bạn đọc).*$""",
            replacement = "",
            isRegex = true,
            enabled = true
        ),
        PronunciationRuleEntity(
            id = UUID.randomUUID().toString(),
            pattern = """https?://\S+""",
            replacement = "",
            isRegex = true,
            enabled = true
        ),
        PronunciationRuleEntity(
            id = UUID.randomUUID().toString(),
            pattern = "Heavy Object",
            replacement = "He-vi Óp-zếch",
            isRegex = false,
            enabled = true
        )
    )
}
