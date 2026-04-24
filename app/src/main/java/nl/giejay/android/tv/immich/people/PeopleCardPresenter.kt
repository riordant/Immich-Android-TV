package nl.giejay.android.tv.immich.people

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.card.Card

class PeopleCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_people_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as Card
        val view = viewHolder.view
        val imageView = view.findViewById<ImageView>(R.id.avatar_image)
        val nameView = view.findViewById<TextView>(R.id.person_name)

        nameView.text = card.title

        Glide.with(view.context)
            .load(card.thumbnailUrl)
            .circleCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(imageView)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val imageView = viewHolder.view.findViewById<ImageView>(R.id.avatar_image)
        Glide.with(viewHolder.view.context).clear(imageView)
    }
}
