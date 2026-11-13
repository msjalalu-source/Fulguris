package fulguris.html.history

import fulguris.App
import fulguris.R
import fulguris.constant.FILE
import fulguris.database.history.HistoryRepository
import fulguris.html.HtmlPageFactory
import fulguris.html.ListPageReader
import fulguris.utils.ThemeUtils
import fulguris.utils.htmlColor
import android.app.Application
import dagger.Reusable
import fulguris.html.jsoup.andBuild
import fulguris.html.jsoup.body
import fulguris.html.jsoup.clone
import fulguris.html.jsoup.id
import fulguris.html.jsoup.parse
import fulguris.html.jsoup.removeElement
import fulguris.html.jsoup.tag
import fulguris.html.jsoup.title
import io.reactivex.Completable
import io.reactivex.Single
import java.io.File
import java.io.FileWriter
import javax.inject.Inject

/**
 * Factory for the history page.
 */
@Reusable
class HistoryPageFactory @Inject constructor(
    private val listPageReader: ListPageReader,
    private val application: Application,
    private val historyRepository: HistoryRepository
) : HtmlPageFactory {

    private val title = application.getString(R.string.action_history)

    /** Extra style for the Chrome like history page, theme colors are replaced later with the others. */
    private fun historyCss(): String =
        ".head{display:flex;align-items:center;justify-content:space-between;padding:22px 16px 10px 16px}" +
        ".headTitle{font-family:Arial;font-size:26px;color:\${textColor}}" +
        ".clearAll{display:flex;align-items:center;font-family:Arial;font-size:14px;color:\${textColor};text-decoration:none;padding:8px 14px;border:1px solid \${dividerColor};border-radius:18px}" +
        ".clearAll svg{width:18px;height:18px;margin-right:6px;fill:\${textColor}}" +
        ".box{margin-left:0;margin-right:0;padding:10px 16px 10px 70px;border-bottom:none;min-height:40px}" +
        ".entryTitle{font-size:16px;margin:0}" +
        ".entryUrl{font-size:13px;margin:2px 0 0 0}" +
        ".fav{position:absolute;left:16px;top:50%;margin-top:-18px;width:36px;height:36px;box-sizing:border-box;padding:7px;border-radius:50%;background:rgba(128,128,128,0.2);object-fit:contain;pointer-events:none}"

    /** Header of the history page: the name on one side and the delete all button on the other. */
    private fun historyHeader(): String =
        "<div class='head'><span class='headTitle'>" + android.text.TextUtils.htmlEncode(title) + "</span>" +
        "<a class='clearAll' href='fulguris://clear-history'>" +
        "<svg viewBox='0 0 24 24'><path d='M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z'/></svg>" +
        "<span>" + android.text.TextUtils.htmlEncode(application.getString(R.string.title_clear_history)) + "</span></a></div>"

    override fun buildPage(): Single<String> = historyRepository
        .lastHundredVisitedHistoryEntries()
        .map { list ->
            parse(listPageReader.provideHtml()
                    // Show localized page title
                    .replace("\${pageTitle}", application.getString(R.string.action_history))
                    // Chrome like look: a site icon on each row and a header with the delete all button
                    .replace("<a href='\${URL}'></a>", "<a href='\${URL}'></a><img class='fav' src=''>")
                    .replace("</style>", historyCss() + "</style>")
                    .replace("<div id=\"content\">", historyHeader() + "<div id=\"content\">")
                    // Theme our page first
                    .replace("\${backgroundColor}", htmlColor(ThemeUtils.getSurfaceColor(App.currentContext())))
                    .replace("\${textColor}", htmlColor(ThemeUtils.getColor(App.currentContext(),R.attr.colorOnSurface)))
                    .replace("\${secondaryTextColor}", htmlColor(ThemeUtils.getColor(App.currentContext(),R.attr.colorSecondary)))
                    .replace("\${dividerColor}", htmlColor(ThemeUtils.getColor(App.currentContext(),R.attr.colorOutline)))
            ) andBuild {
                title { title }
                body {
                    val repeatedElement = id("repeated").removeElement()
                    id("content") {
                        list.forEach {
                            appendChild(repeatedElement.clone {
                                val siteHost = android.net.Uri.parse(it.url).host
                                tag("a") { attr("href", it.url) }
                                tag("img") {
                                    if (siteHost.isNullOrEmpty()) {
                                        attr("style", "display:none")
                                    } else {
                                        attr("src", "https://icons.duckduckgo.com/ip3/$siteHost.ico")
                                        attr("onerror", "this.style.visibility='hidden'")
                                    }
                                }
                                id("title") { text(it.title) }
                                // Only the site name like Chrome, not the whole address
                                id("url") { text(if (siteHost.isNullOrEmpty()) it.url else siteHost) }
                            })
                        }
                    }
                }
            }
        }
        .map { content -> Pair(createHistoryPage(), content) }
        .doOnSuccess { (page, content) ->
            FileWriter(page, false).use { it.write(content) }
        }
        .map { (page, _) -> "$FILE$page" }

    /**
     * Use this observable to immediately delete the history page. This will clear the cached
     * history page that was stored on file.
     *
     * @return a completable that deletes the history page when subscribed to.
     */
    fun deleteHistoryPage(): Completable = Completable.fromAction {
        with(createHistoryPage()) {
            if (exists()) {
                delete()
            }
        }
    }

    private fun createHistoryPage() = File(application.filesDir, FILENAME)

    companion object {
        const val FILENAME = "history.html"
    }

}
