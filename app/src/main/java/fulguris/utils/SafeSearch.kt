package fulguris.utils

import android.net.Uri

/**
 * Safe search lock: every web search is sent to DuckDuckGo Safe (safe.duckduckgo.com).
 */
object SafeSearch {

    private const val SAFE_HOME = "https://safe.duckduckgo.com/"
    private const val SAFE_QUERY = "https://safe.duckduckgo.com/?q="

    // Search engines that are sent to the safe one on any page
    private val otherEngines = listOf(
        "bing.com", "search.yahoo.com", "yandex.com", "yandex.ru", "baidu.com",
        "ecosia.org", "search.brave.com", "startpage.com", "qwant.com",
        "ask.com", "search.naver.com", "mojeek.com", "searx.be"
    )

    // Plain DuckDuckGo addresses that do not have the safe search lock
    private val plainDuck = listOf(
        "duckduckgo.com", "html.duckduckgo.com", "lite.duckduckgo.com", "start.duckduckgo.com"
    )

    private val googleHost = Regex("^google\\.(com?\\.)?[a-z]{2,3}$")
    private val queryKeys = listOf("q", "p", "text", "wd", "query", "s")

    private var lastRedirect = 0L

    /** Returns the safe address to load instead, or null when the page can load as it is. */
    fun redirectFor(url: String): String? {
        val uri = try { Uri.parse(url) } catch (e: Exception) { return null }
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val host = (uri.host ?: return null).lowercase().removePrefix("www.").removePrefix("m.")
        val path = uri.path ?: ""

        val isOtherSearch = when {
            // Google: only its search page, so Gmail, Translate and AI Studio keep working
            googleHost.matches(host) -> path.startsWith("/search")
            host in plainDuck -> true
            else -> otherEngines.any { host == it || host.endsWith(".$it") }
        }
        if (!isOtherSearch) return null

        // Never allow a redirect loop
        val now = System.currentTimeMillis()
        if (now - lastRedirect < 1500) return null
        lastRedirect = now

        val query = queryKeys.firstNotNullOfOrNull { key -> uri.getQueryParameter(key)?.takeIf { it.isNotBlank() } }
        return if (query == null) SAFE_HOME else SAFE_QUERY + Uri.encode(query)
    }
}
