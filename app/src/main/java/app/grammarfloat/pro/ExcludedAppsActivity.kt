package app.grammarfloat.pro

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExcludedAppsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: AppExclusionAdapter

    companion object {
        const val PREFS_NAME = "grammar_float_prefs"
        const val KEY_EXCLUDED_APPS = "excluded_apps"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_excluded_apps)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)

        recyclerView.layoutManager = LinearLayoutManager(this)
        
        // Initialize with empty list
        adapter = AppExclusionAdapter(emptyList()) { appItem, isExcluded ->
            updateExclusionPreference(appItem.packageName, isExcluded)
        }
        recyclerView.adapter = adapter

        loadApps()
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch {
            val appList = withContext(Dispatchers.IO) {
                val pm = packageManager
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val excludedSet = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()

                val items = mutableListOf<AppItem>()
                for (appInfo in packages) {
                    // Filter out system apps, except if we want to show everything.
                    // Usually users want to exclude standard apps, but some might be system apps.
                    // Let's filter out standard system apps that don't have launcher intents, 
                    // or just show apps that have a launch intent to keep the list clean.
                    if (pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                        val name = pm.getApplicationLabel(appInfo).toString()
                        val icon = pm.getApplicationIcon(appInfo)
                        val isExcluded = excludedSet.contains(appInfo.packageName)
                        items.add(AppItem(name, appInfo.packageName, icon, isExcluded))
                    }
                }
                
                // Sort alphabetically
                items.sortBy { it.name.lowercase() }
                items
            }

            adapter.updateData(appList)
            progressBar.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun updateExclusionPreference(packageName: String, isExcluded: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentSet = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()
        
        val newSet = HashSet(currentSet)
        if (isExcluded) {
            newSet.add(packageName)
        } else {
            newSet.remove(packageName)
        }
        
        prefs.edit().putStringSet(KEY_EXCLUDED_APPS, newSet).apply()
    }
}
