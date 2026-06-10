package app.grammarfloat.pro

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isExcluded: Boolean
)

class AppExclusionAdapter(
    private var apps: List<AppItem>,
    private val onExclusionChanged: (AppItem, Boolean) -> Unit
) : RecyclerView.Adapter<AppExclusionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAppIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvAppPackage: TextView = view.findViewById(R.id.tvAppPackage)
        val switchExclude: SwitchMaterial = view.findViewById(R.id.switchExclude)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_exclusion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.ivAppIcon.setImageDrawable(app.icon)
        holder.tvAppName.text = app.name
        holder.tvAppPackage.text = app.packageName
        
        // Remove listener before setting checked state to avoid unwanted triggers
        holder.switchExclude.setOnCheckedChangeListener(null)
        holder.switchExclude.isChecked = app.isExcluded
        
        holder.switchExclude.setOnCheckedChangeListener { _, isChecked ->
            app.isExcluded = isChecked
            onExclusionChanged(app, isChecked)
        }

        // Toggle switch when the whole row is clicked
        holder.itemView.setOnClickListener {
            holder.switchExclude.isChecked = !holder.switchExclude.isChecked
        }
    }

    override fun getItemCount() = apps.size

    fun updateData(newApps: List<AppItem>) {
        apps = newApps
        notifyDataSetChanged()
    }
}
