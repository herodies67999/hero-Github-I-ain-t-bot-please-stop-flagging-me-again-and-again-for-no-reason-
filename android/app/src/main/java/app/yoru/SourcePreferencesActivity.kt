package app.yoru

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.anymex.runtimehost.RuntimeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uses the source's own preference schema and callbacks. */
class SourcePreferencesActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SourcePreferencesFragment())
                .commit()
        }
    }
}

class SourcePreferencesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(state: Bundle?, rootKey: String?) {
        val source = requireActivity().intent.getStringExtra("source").orEmpty()
        preferenceManager.sharedPreferencesName = "source_$source"
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        val entries =
            RuntimeBridge.aniyomiGetPreference(
                requireContext(),
                source,
                requireActivity().intent.getStringExtra("type") == "aniyomi",
            )
        if (entries.isEmpty())
            preferenceScreen.addPreference(
                Preference(requireContext()).apply { title = "This source has no settings" }
            )
        entries.forEach { entry ->
            val key = entry["key"] as? String ?: return@forEach
            val p =
                when (entry["type"]) {
                    "switch" ->
                        SwitchPreferenceCompat(requireContext()).apply {
                            isChecked = entry["value"] == true
                        }
                    "checkbox" ->
                        CheckBoxPreference(requireContext()).apply {
                            isChecked = entry["value"] == true
                        }
                    "text" ->
                        EditTextPreference(requireContext()).apply {
                            text = entry["value"] as? String
                        }
                    "list" ->
                        ListPreference(requireContext()).apply {
                            this.entries =
                                (entry["entries"] as? List<*>)
                                    ?.map { it.toString() }
                                    ?.toTypedArray()
                            entryValues =
                                (entry["entryValues"] as? List<*>)
                                    ?.map { it.toString() }
                                    ?.toTypedArray()
                            value = entry["value"] as? String
                        }
                    "multi_select" ->
                        MultiSelectListPreference(requireContext()).apply {
                            this.entries =
                                (entry["entries"] as? List<*>)
                                    ?.map { it.toString() }
                                    ?.toTypedArray()
                            entryValues =
                                (entry["entryValues"] as? List<*>)
                                    ?.map { it.toString() }
                                    ?.toTypedArray()
                            values =
                                (entry["value"] as? List<*>)?.map { it.toString() }?.toSet()
                                    ?: emptySet()
                        }
                    else -> Preference(requireContext())
                }
            p.key = key
            p.title = entry["title"]?.toString()
            p.summary = entry["summary"]?.toString()
            p.isEnabled = entry["enabled"] != false
            p.isPersistent = false
            p.setOnPreferenceChangeListener { _, value ->
                lifecycleScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            RuntimeBridge.aniyomiSavePreference(
                                requireContext(),
                                source,
                                key,
                                "change",
                                if (value is Set<*>) value.toList() else value,
                            )
                        }
                    }
                    if (result.getOrDefault(false)) {
                        when (p) {
                            is SwitchPreferenceCompat -> p.isChecked = value as Boolean
                            is CheckBoxPreference -> p.isChecked = value as Boolean
                            is EditTextPreference -> p.text = value as String
                            is ListPreference -> p.value = value as String
                            is MultiSelectListPreference ->
                                p.values = (value as Set<*>).filterIsInstance<String>().toSet()
                        }
                    } else
                        Toast.makeText(context, "Setting could not be saved", Toast.LENGTH_LONG)
                            .show()
                }
                false
            }
            if (entry["type"] == "other")
                p.setOnPreferenceClickListener {
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            RuntimeBridge.aniyomiSavePreference(
                                requireContext(),
                                source,
                                key,
                                "click",
                                null,
                            )
                        }
                    }
                    true
                }
            preferenceScreen.addPreference(p)
        }
    }
}
