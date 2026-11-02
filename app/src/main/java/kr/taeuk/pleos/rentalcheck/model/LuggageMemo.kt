package kr.taeuk.pleos.rentalcheck.model

data class LuggageMemo(val space: StorageSpace, val item: String) {
    init { require(item.isNotBlank() && item == item.trim() && item.length <= 40 && item.none(Char::isISOControl)) }
}

data class LuggageDraft(val transcript: String = "", val space: StorageSpace? = null, val item: String = "") {
    // Only an explicitly reviewed location and bounded description can become a saved memo; recognition alone never saves anything.
    fun confirmed(): LuggageMemo? = space?.let { runCatching { LuggageMemo(it, item.trim()) }.getOrNull() }

    companion object {
        // Offer a conservative Korean suggestion, leaving ambiguous locations unselected so the driver must resolve them on screen.
        fun fromSpeech(text: String): LuggageDraft {
            val words = text.trim().take(200)
            val aliases = mapOf(StorageSpace.REAR_RIGHT to listOf("뒤 오른쪽", "뒷좌석 오른쪽", "뒤쪽 오른쪽"),
                StorageSpace.REAR_LEFT to listOf("뒤 왼쪽", "뒷좌석 왼쪽", "뒤쪽 왼쪽"),
                StorageSpace.FRONT_LEFT to listOf("운전석"), StorageSpace.FRONT_RIGHT to listOf("동승석", "조수석"),
                StorageSpace.TRUNK to listOf("트렁크"))
            val matches = aliases.flatMap { (space, labels) -> labels.filter { words.contains(it) }.map { space to it } }
            val place = matches.map { it.first }.distinct().singleOrNull()
            val alias = matches.filter { it.first == place }.maxByOrNull { it.second.length }?.second
            val item = if (alias == null) "" else words.substringAfter(alias).replace(Regex("^(좌석|주변)?\\s*(에다가|에다|에는|에|의)?\\s*"), "")
                .replace(Regex("\\s*(을|를)?\\s*(두었어요|뒀어요|두었어|뒀어|뒀습니다|두었습니다|놓았어요|놨어요|놓았어|놨어|보관했어요)[.!?]?$"), "").trim()
            return LuggageDraft(words, place, item.take(40))
        }
    }
}
