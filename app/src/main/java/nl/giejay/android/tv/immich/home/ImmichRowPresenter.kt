package nl.giejay.android.tv.immich.home

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import nl.giejay.android.tv.immich.R

class ImmichRowPresenter : Presenter() {
    var onNavigateToSidebarControls: ((Row, Int) -> Boolean)? = null

    override fun onCreateViewHolder(parent: ViewGroup): ImmichRowViewHolder {
        val root: View = LayoutInflater.from(parent.context)
            .inflate(R.layout.presenter_row, parent, false)

        val viewHolder = ImmichRowViewHolder(root)
        return viewHolder
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val headerItem = if (item == null) null else (item as Row).headerItem
        val vh = viewHolder as ImmichRowViewHolder
        vh.tvTitle.text = headerItem?.name
        vh.icon.visibility = View.GONE

        val row = item as? Row
        val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (
                event?.action == KeyEvent.ACTION_DOWN &&
                row != null &&
                (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
            ) {
                return@OnKeyListener onNavigateToSidebarControls?.invoke(row, keyCode) == true
            }

            false
        }

        vh.view.setOnKeyListener(keyListener)
        vh.tvTitle.setOnKeyListener(keyListener)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val vh = viewHolder as ImmichRowViewHolder
        vh.tvTitle.text = null
        vh.view.setOnKeyListener(null)
        vh.tvTitle.setOnKeyListener(null)
    }

}
