package fulguris.settings.fragment

import android.os.Bundle
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import fulguris.R
import fulguris.settings.preferences.UserPreferences
import fulguris.utils.KeywordBlocker
import javax.inject.Inject

/**
 * Shows that blocked words exist, the words themselves cannot be changed here.
 */
@AndroidEntryPoint
class KeywordsSettingsFragment : AbstractSettingsFragment() {

    @Inject internal lateinit var userPreferences: UserPreferences

    override fun titleResourceId(): Int {
        return R.string.settings_keywords
    }

    override fun providePreferencesXmlResource() = R.xml.preference_keywords

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>("pref_key_keywords_info")?.summary = getString(R.string.pref_summary_keywords_info, KeywordBlocker.count())
    }
}
