package dev.bill.openclone

import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The three-tab bottom bar shared by Beranda, Riwayat, and Pengaturan
 * (DESAIN.md section 4).
 *
 * Tabs are separate activities rather than fragments, so switching is a
 * [Intent] with [Intent.FLAG_ACTIVITY_REORDER_TO_FRONT]: the target screen is
 * brought forward if it already exists, which keeps each tab's scroll and
 * search state instead of rebuilding it. The activity hosting the bar tells
 * the view which tab is current via [select]; taps on that same tab do
 * nothing.
 */
class BottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Tab { APPS, HISTORY, SETTINGS }

    private val items: Map<Tab, View>
    private val icons: Map<Tab, ImageView>
    private val labels: Map<Tab, TextView>

    private var selected: Tab? = null

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_bottom_nav, this, true)

        items = mapOf(
            Tab.APPS to findViewById(R.id.navApps),
            Tab.HISTORY to findViewById(R.id.navHistory),
            Tab.SETTINGS to findViewById(R.id.navSettings),
        )
        icons = mapOf(
            Tab.APPS to findViewById(R.id.navAppsIcon),
            Tab.HISTORY to findViewById(R.id.navHistoryIcon),
            Tab.SETTINGS to findViewById(R.id.navSettingsIcon),
        )
        labels = mapOf(
            Tab.APPS to findViewById(R.id.navAppsLabel),
            Tab.HISTORY to findViewById(R.id.navHistoryLabel),
            Tab.SETTINGS to findViewById(R.id.navSettingsLabel),
        )

        items.forEach { (tab, view) ->
            view.setOnClickListener {
                if (tab != selected) {
                    context.startActivity(
                        Intent(context, activityFor(tab)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        },
                    )
                }
            }
        }
    }

    /** Marks [tab] as active and tints the other two as muted. */
    fun select(tab: Tab) {
        selected = tab
        val active = ContextCompat.getColor(context, R.color.accent_white)
        val inactive = ContextCompat.getColor(context, R.color.text_muted)
        items.forEach { (candidate, view) ->
            val isActive = candidate == tab
            val tint = if (isActive) active else inactive
            icons[candidate]?.let { icon -> icon.tint(tint) }
            labels[candidate]?.let { label ->
                label.setTextColor(tint)
                label.isSelected = isActive
            }
            view.isSelected = isActive
        }
    }

    private fun ImageView.tint(color: Int) {
        val drawable: Drawable = drawable ?: return
        drawable.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun activityFor(tab: Tab): Class<*> = when (tab) {
        Tab.APPS -> MainActivity::class.java
        Tab.HISTORY -> HistoryActivity::class.java
        Tab.SETTINGS -> SettingsActivity::class.java
    }
}
