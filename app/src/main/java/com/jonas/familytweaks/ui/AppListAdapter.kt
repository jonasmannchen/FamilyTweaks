package com.jonas.familytweaks.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jonas.familytweaks.R
import com.jonas.familytweaks.data.AppInfo
import com.jonas.familytweaks.data.AdminStatus

/**
 * Performant list adapter for both tabs. ListAdapter + DiffUtil guarantees
 * that only changed rows are re-bound when a new scan result arrives.
 *
 * Icons are loaded lazily per visible row by [IconLoader]; the adapter itself
 * stays free of any PackageManager calls.
 */
class AppListAdapter(
    private val onClick: (AppInfo) -> Unit,
    private val onSelectionToggled: (AppInfo, Boolean) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.ViewHolder>(DIFF) {

    companion object {
        /**
         * Equality covers EVERY field rendered by the row, including the
         * framework admin status and the (locally stored) selection flag, so
         * DiffUtil cannot miss an update. Labels/packages/flags alone would
         * hide status changes.
         */
        private val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.appIcon)
        private val name: TextView = itemView.findViewById(R.id.appName)
        private val pkg: TextView = itemView.findViewById(R.id.appPackage)
        private val statusBadge: TextView = itemView.findViewById(R.id.adminBadge)
        private val disabledTag: TextView = itemView.findViewById(R.id.disabledTag)
        private val check: CheckBox = itemView.findViewById(R.id.appCheck)

        fun bind(item: AppInfo) {
            name.text = item.label
            pkg.text = item.packageName

            // Status badge: strictly derived from what ANDROID reported.
            when (item.adminStatus) {
                AdminStatus.ADMIN_ACTIVE -> {
                    statusBadge.setText(R.string.status_admin_active)
                    statusBadge.setBackgroundResource(R.drawable.badge_active)
                }
                AdminStatus.ADMIN_AVAILABLE -> {
                    statusBadge.setText(R.string.status_admin_available)
                    statusBadge.setBackgroundResource(R.drawable.badge_available)
                }
                AdminStatus.NO_ADMIN -> {
                    statusBadge.setText(R.string.status_no_admin)
                    statusBadge.setBackgroundResource(R.drawable.badge_none)
                }
            }

            disabledTag.visibility = if (item.isEnabled) View.GONE else View.VISIBLE

            // Keep checkbox visually in sync with the STORED selection,
            // without stealing clicks from the row.
            check.setOnCheckedChangeListener(null)
            check.isChecked = item.isSelected
            check.setOnCheckedChangeListener { _, checked ->
                onSelectionToggled(item, checked)
            }

            IconLoader.load(itemView.context, item.packageName, icon)

            itemView.setOnClickListener { onClick(item) }
        }
    }
}

/**
 * Loads app icons asynchronously keyed by package name. Uses the view-tag
 * trick plus a small LruCache so recycled rows can never show a wrong icon,
 * and a single shared background executor instead of one thread per row.
 */
object IconLoader {

    private val cache = android.util.LruCache<String, android.graphics.drawable.Drawable>(80)

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ft-icons").apply { isDaemon = true }
    }

    fun load(context: Context, packageName: String, target: ImageView) {
        target.tag = packageName
        val cached = cache.get(packageName)
        if (cached != null) {
            target.setImageDrawable(cached)
            return
        }
        target.setImageResource(R.drawable.ic_placeholder)
        val appContext = context.applicationContext
        executor.execute {
            val drawable = try {
                @Suppress("DEPRECATION")
                appContext.packageManager.getApplicationIcon(packageName)
            } catch (e: Exception) {
                null // uninstalled meanwhile or broken icon: keep placeholder
            }
            if (drawable != null) cache.put(packageName, drawable)
            target.post {
                if (target.tag == packageName && drawable != null) {
                    target.setImageDrawable(drawable)
                }
            }
        }
    }
}
