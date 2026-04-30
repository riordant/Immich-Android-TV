package nl.giejay.android.tv.immich.card

data class Card(
    override val title: String,
    override val description: String?,
    override val id: String,
    override val thumbnailUrl: String?,
    override val backgroundUrl: String?,
    val isVideo: Boolean = false,
    var isFavorite: Boolean = false, // <--- ESTE ES EL CAMPO QUE FALTABA
    override var selected: Boolean = false,
    override val forceShowInfo: Boolean = false,
    val videoDuration: String? = null,
    val videoPlaybackPositionSeconds: Int? = null
) : ICard
