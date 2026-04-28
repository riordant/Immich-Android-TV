package nl.giejay.android.tv.immich.api.model

data class RecentVideoUpdateRequest(
    val assetId: String
)

data class VideoPlaybackUpdateRequest(
    val assetId: String,
    val positionSeconds: Int
)

data class VideoPlaybackResponse(
    val assetId: String,
    val positionSeconds: Int?
)

data class VideoPlaybackEntry(
    val assetId: String,
    val positionSeconds: Int
)
