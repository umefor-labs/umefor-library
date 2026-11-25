package io.lightknight.omniread.engine.tts

/**
 * Zero-cost offline dialogue splitter for Vietnamese and English novels.
 * Separates narration from quoted speech ("...", “...”, «...», leading — / -)
 * and applies simple speech-verb heuristics to tag named speakers when nearby.
 */
object LocalDialogueSplitter {
    private val quoteRegex = Regex("""[“"]([^”"\n]+)[”"]|«([^»\n]+)»""")
    private val dashLineRegex = Regex("""^\s*[—\-–]\s*(.+)$""")
    private val speakerHintRegex = Regex(
        """([\p{Lu}][\p{L}\d]+(?:\s+[\p{Lu}][\p{L}\d]+){0,2})\s+(?:nói|hỏi|đáp|trả lời|cười|thì thầm|quát|hét|lẩm bẩm|bảo|nhắn|said|asked|replied)\b"""
    )

    fun split(paragraphs: List<String>): List<DialogueSegment> {
        val segments = mutableListOf<DialogueSegment>()
        var lastDetectedSpeaker = "Nhân vật thoại"

        paragraphs.forEachIndexed { pIdx, rawPara ->
            val para = rawPara.trim()
            if (para.isEmpty()) return@forEachIndexed

            // Detect speaker name hint in this paragraph for nearby dialogue
            speakerHintRegex.find(para)?.groupValues?.getOrNull(1)?.let { candidate ->
                if (candidate.length >= 2) lastDetectedSpeaker = candidate
            }

            val dashMatch = dashLineRegex.matchEntire(para)
            if (dashMatch != null) {
                val spoken = dashMatch.groupValues[1].trim()
                if (spoken.isNotEmpty()) {
                    segments += DialogueSegment(
                        index = segments.size,
                        speaker = lastDetectedSpeaker,
                        text = spoken,
                        paragraphIndex = pIdx
                    )
                }
                return@forEachIndexed
            }

            val matches = quoteRegex.findAll(para).toList()
            if (matches.isEmpty()) {
                segments += DialogueSegment(
                    index = segments.size,
                    speaker = NARRATOR_NAME,
                    text = para,
                    paragraphIndex = pIdx
                )
            } else {
                var cursor = 0
                for (m in matches) {
                    if (m.range.first > cursor) {
                        val narr = para.substring(cursor, m.range.first).trim()
                        if (narr.isNotEmpty()) {
                            speakerHintRegex.find(narr)?.groupValues?.getOrNull(1)?.let {
                                lastDetectedSpeaker = it
                            }
                            segments += DialogueSegment(
                                index = segments.size,
                                speaker = NARRATOR_NAME,
                                text = narr,
                                paragraphIndex = pIdx
                            )
                        }
                    }
                    val spoken = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
                    if (spoken.isNotEmpty()) {
                        segments += DialogueSegment(
                            index = segments.size,
                            speaker = lastDetectedSpeaker,
                            text = spoken,
                            paragraphIndex = pIdx
                        )
                    }
                    cursor = m.range.last + 1
                }
                if (cursor < para.length) {
                    val tail = para.substring(cursor).trim()
                    if (tail.isNotEmpty()) {
                        segments += DialogueSegment(
                            index = segments.size,
                            speaker = NARRATOR_NAME,
                            text = tail,
                            paragraphIndex = pIdx
                        )
                    }
                }
            }
        }
        return segments
    }

    const val NARRATOR_NAME = "Người dẫn chuyện"
}
