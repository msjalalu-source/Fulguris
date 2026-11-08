package fulguris.utils

import android.net.Uri

/**
 * Blocks searches that contain one of our fixed words.
 * The list is part of the app, there is no way for the user to add, edit or remove words.
 * Only what is searched for is checked, not the whole address, so normal pages are not affected.
 */
object KeywordBlocker {

    /** Blocked words, fixed in the app. */
    private val KEYWORDS = listOf("hot", "intimate", "adult", "kiss", "video", "anonymous", "anonymity", "18+", "apk")

    /** Address parameters that carry what the user searched for. */
    private val QUERY_PARAMS = setOf(
        "q", "query", "search_query", "search", "s", "p", "text", "k", "wd",
        "keyword", "keywords", "term", "searchterm", "qs", "kw", "find", "ss"
    )

    /** A word matches as a whole word, with an optional plural s or es at the end. */
    private val PATTERNS: List<Regex> = KEYWORDS.map { word ->
        val escaped = Regex.escape(word.lowercase())
        val plural = if (word.last().isLetter()) "(?:s|es)?" else ""
        Regex("(?<![\\p{L}\\p{N}])" + escaped + plural + "(?![\\p{L}\\p{N}])")
    }

    /** How many words are blocked, for the settings page. */
    fun count(): Int = KEYWORDS.size

    /** True when this address is a web search for a blocked word. */
    fun isBlocked(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        return try {
            val uri = Uri.parse(url)
            uri.queryParameterNames.any { name ->
                name.lowercase() in QUERY_PARAMS && uri.getQueryParameters(name).any { value ->
                    val text = value.lowercase()
                    PATTERNS.any { it.containsMatchIn(text) }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** The page we show instead of the blocked search. */
    fun blockedPageHtml(): String = "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>body{margin:0;background:#12151C;color:#E5E7EB;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center}" +
        "h1{font-size:20px;margin:0 0 8px}p{margin:0;color:#9CA3AF;font-size:14px}</style></head>" +
        "<body><div><h1>Blocked</h1><p>This search contains a word that is blocked in this browser.</p></div></body></html>"
}
