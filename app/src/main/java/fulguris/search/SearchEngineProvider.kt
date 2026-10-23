package fulguris.search

import fulguris.di.SuggestionsClient
import fulguris.settings.preferences.UserPreferences
import android.app.Application
import dagger.Reusable
import fulguris.search.engine.BaseSearchEngine
import fulguris.search.engine.CustomSearch
import fulguris.search.engine.DuckSearch
import fulguris.search.engine.GoogleSearch
import fulguris.search.suggestions.BaiduSuggestionsModel
import fulguris.search.suggestions.DuckSuggestionsModel
import fulguris.search.suggestions.GoogleSuggestionsModel
import fulguris.search.suggestions.NaverSuggestionsModel
import fulguris.search.suggestions.NoOpSuggestionsRepository
import fulguris.search.suggestions.RequestFactory
import fulguris.search.suggestions.SuggestionsRepository
import io.reactivex.Single
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * The model that provides the search engine based
 * on the user's preference.
 */
@Reusable
class SearchEngineProvider @Inject constructor(
    private val userPreferences: UserPreferences,
    @SuggestionsClient private val okHttpClient: Single<OkHttpClient>,
    private val requestFactory: RequestFactory,
    private val application: Application
) {

    /**
     * Provide the [SuggestionsRepository] that maps to the user's current preference.
     */
    fun provideSearchSuggestions(): SuggestionsRepository =
        when (userPreferences.searchSuggestionChoice) {
            0 -> NoOpSuggestionsRepository()
            1 -> GoogleSuggestionsModel(okHttpClient, requestFactory, application, userPreferences)
            2 -> DuckSuggestionsModel(okHttpClient, requestFactory, application, userPreferences)
            3 -> BaiduSuggestionsModel(okHttpClient, requestFactory, application, userPreferences)
            4 -> NaverSuggestionsModel(okHttpClient, requestFactory, application, userPreferences)
            else -> GoogleSuggestionsModel(okHttpClient, requestFactory, application, userPreferences)
        }

    /**
     * Provide the [BaseSearchEngine] that maps to the user's current preference.
     * Only DuckDuckGo (default) and Google are supported now.
     */
    fun provideSearchEngine(): BaseSearchEngine =
        when (userPreferences.searchChoice) {
            0 -> CustomSearch(userPreferences.searchUrl, userPreferences)
            1 -> GoogleSearch()
            6 -> DuckSearch()
            else -> DuckSearch()
        }

    /**
     * Return the serializable index of of the provided [BaseSearchEngine].
     */
    fun mapSearchEngineToPreferenceIndex(searchEngine: BaseSearchEngine): Int =
        when (searchEngine) {
            is CustomSearch -> 0
            is GoogleSearch -> 1
            is DuckSearch -> 6
            else -> throw UnsupportedOperationException("Unknown search engine provided: " + searchEngine.javaClass)
        }

    /**
     * Provide a list of all supported search engines.
     */
    fun provideAllSearchEngines(): List<BaseSearchEngine> = listOf(
        DuckSearch(),
        GoogleSearch()
    )

}
