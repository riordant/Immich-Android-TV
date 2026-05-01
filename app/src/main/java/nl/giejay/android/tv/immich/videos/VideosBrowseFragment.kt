package nl.giejay.android.tv.immich.videos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseCardView
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.RowPresenter
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.album.AlbumDetailsFragmentDirections
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.card.CardPresenter
import nl.giejay.android.tv.immich.card.ICard
import nl.giejay.android.tv.immich.home.HomeFragment
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ANIMATE_ASSET_SLIDE
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ANIMATION_SPEED
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_GLIDE_TRANSFORMATION
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_HEIGHT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_WIDTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ONLY_USE_THUMBNAILS
import nl.giejay.android.tv.immich.shared.util.toCard
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItemViewHolder
import timber.log.Timber
import java.util.Calendar
import java.util.Date

private data class VideosCacheKey(
    val hostName: String,
    val apiKey: String
)

private data class VideosCacheValue(
    val assets: List<Asset>,
    val recentAssets: List<Asset>,
    val playbackPositions: Map<String, Int>
)

private object VideosCache {
    private var key: VideosCacheKey? = null
    private var value: VideosCacheValue? = null

    fun get(requestKey: VideosCacheKey): VideosCacheValue? {
        return if (key == requestKey) value else null
    }

    fun put(requestKey: VideosCacheKey, cacheValue: VideosCacheValue) {
        key = requestKey
        value = cacheValue
    }

    fun clear() {
        key = null
        value = null
    }
}

class VideosBrowseFragment : RowsSupportFragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val ioScope = CoroutineScope(Job() + Dispatchers.IO)
    private lateinit var apiClient: ApiClient
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private var loadingIndicator: ProgressBar? = null
    private var sliderItems: List<SliderItemViewHolder> = emptyList()
    private var videoAssets: List<Asset> = emptyList()
    private var recentVideoAssets: List<Asset> = emptyList()
    private var videoPlaybackPositions: Map<String, Int> = emptyMap()
    private var hasLoadedVideos = false
    private var isRefreshingRecentState = false

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> {
        return mMainFragmentAdapter
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rowsView = super.onCreateView(inflater, container, savedInstanceState)
        return FrameLayout(requireContext()).apply {
            addView(
                rowsView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            loadingIndicator = ProgressBar(context).apply {
                isIndeterminate = true
            }
            addView(
                loadingIndicator,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setAlignment(resources.getDimensionPixelSize(androidx.leanback.R.dimen.lb_browse_rows_margin_top))

        rowsAdapter = ArrayObjectAdapter(VideosListRowPresenter())
        adapter = rowsAdapter
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            updateSelectedTitle(item)
        }

        setupClient()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        clearHomeTitle()
        showLoading(true)
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        if (hasLoadedVideos) {
            refreshRecentState()
        }
    }

    override fun onDestroyView() {
        loadingIndicator = null
        clearHomeTitle()
        super.onDestroyView()
    }

    private fun setupClient() {
        apiClient = ApiClient.getClient(
            ApiClientConfig(
                PreferenceManager.get(HOST_NAME),
                PreferenceManager.get(API_KEY),
                PreferenceManager.get(DISABLE_SSL_VERIFICATION),
                PreferenceManager.get(DEBUG_MODE)
            )
        )
    }

    private fun getDateFromAsset(asset: Asset): Date? {
        return asset.exifInfo?.dateTimeOriginal ?: asset.fileModifiedAt
    }

    private fun cacheKey(): VideosCacheKey {
        return VideosCacheKey(
            hostName = PreferenceManager.get(HOST_NAME),
            apiKey = PreferenceManager.get(API_KEY)
        )
    }

    private fun showLoading(show: Boolean) {
        if (!isAdded) {
            return
        }
        requireActivity().runOnUiThread {
            loadingIndicator?.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun loadVideos() {
        val requestKey = cacheKey()
        VideosCache.get(requestKey)?.let { cached ->
            applyCachedVideos(cached)
            showLoading(false)
            return
        }

        ioScope.launch {
            try {
                val assets = loadAllVideos()
                val recentAssets = loadRecentVideos()
                val playbackPositions = loadVideoPlaybackPositions()
                videoAssets = assets
                recentVideoAssets = recentAssets
                videoPlaybackPositions = playbackPositions
                hasLoadedVideos = true
                VideosCache.put(requestKey, VideosCacheValue(assets, recentAssets, playbackPositions))
                updateSliderItems()
                renderRows(videoAssets, recentVideoAssets)
                showLoading(false)
            } catch (e: Exception) {
                Timber.e(e, "Exception in VideosBrowseFragment")
                showLoading(false)
            }
        }
    }

    private fun applyCachedVideos(cached: VideosCacheValue) {
        videoAssets = cached.assets
        recentVideoAssets = cached.recentAssets
        videoPlaybackPositions = cached.playbackPositions
        hasLoadedVideos = true
        updateSliderItems()
        renderRows(videoAssets, recentVideoAssets)
    }

    private suspend fun loadAllVideos(): List<Asset> {
        val assets = mutableListOf<Asset>()
        var page = 1

        while (true) {
            val result = apiClient.videoAssets(page, PAGE_SIZE)
            val pageAssets = result.fold(
                { error ->
                    Timber.e("Error loading videos: $error")
                    return assets.distinctBy { it.id }
                },
                { loadedAssets -> loadedAssets }
            )

            assets.addAll(pageAssets)

            if (pageAssets.size < PAGE_SIZE) {
                break
            }

            page += 1
        }

        return assets.distinctBy { it.id }
    }

    private suspend fun loadRecentVideos(): List<Asset> {
        return apiClient.getRecentVideos().fold(
            { error ->
                Timber.e("Error loading recent videos: $error")
                emptyList()
            },
            { assets -> assets }
        )
    }

    private suspend fun loadVideoPlaybackPositions(): Map<String, Int> {
        return apiClient.getVideoPlaybacks().fold(
            { error ->
                Timber.e("Error loading video playback positions: $error")
                emptyMap()
            },
            { entries -> entries.associate { it.assetId to it.positionSeconds } }
        )
    }

    private fun refreshRecentState() {
        if (isRefreshingRecentState) {
            return
        }
        isRefreshingRecentState = true

        ioScope.launch {
            try {
                recentVideoAssets = loadRecentVideos()
                videoPlaybackPositions = loadVideoPlaybackPositions()
                VideosCache.put(cacheKey(), VideosCacheValue(videoAssets, recentVideoAssets, videoPlaybackPositions))
                updateSliderItems()
                renderRows(videoAssets, recentVideoAssets)
            } catch (e: Exception) {
                Timber.e(e, "Exception refreshing recent video state")
            } finally {
                isRefreshingRecentState = false
            }
        }
    }

    private fun updateSliderItems() {
        sliderItems = (recentVideoAssets + videoAssets)
            .distinctBy { it.id }
            .toSliderItems(keepOrder = true, mergePortrait = false)
    }

    private fun updateSelectedTitle(item: Any?) {
        if (shouldShowSelectedTitle()) {
            clearHomeTitle()
        }
    }

    fun publishSelectedTitleIfVisible() {
        if (shouldShowSelectedTitle()) {
            clearHomeTitle()
        }
    }

    private fun shouldShowSelectedTitle(): Boolean {
        val homeFragment = parentFragment as? HomeFragment ?: return false
        return !homeFragment.isShowingHeaders && !homeFragment.isInHeadersTransition
    }

    private fun updateHomeTitle(title: String?) {
        (parentFragment as? HomeFragment)?.setDynamicTitle(title)
    }

    private fun clearHomeTitle() {
        updateHomeTitle(null)
    }

    private fun renderRows(assets: List<Asset>, recentAssets: List<Asset>) {
        val groupedAssets = assets.groupBy {
            getDateFromAsset(it)?.let { date ->
                Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
            }
        }
        val years = groupedAssets.keys.filterNotNull().sortedDescending()

        if (!isAdded) {
            return
        }

        requireActivity().runOnUiThread {
            rowsAdapter.clear()
            val cardPresenter = VideosCardPresenter(requireContext())
            cardPresenter.onClick = { clickedCard ->
                if (clickedCard is Card) {
                    onItemClicked(clickedCard)
                }
            }

            if (recentAssets.isNotEmpty()) {
                val recentHeader = HeaderItem(CONTINUE_WATCHING_HEADER_ID, "Continue Watching")
                val recentRowAdapter = ArrayObjectAdapter(cardPresenter)
                recentAssets.forEach { asset ->
                    recentRowAdapter.add(asset.toCard(videoPlaybackPositions[asset.id]))
                }
                rowsAdapter.add(ListRow(recentHeader, recentRowAdapter))
            }

            years.forEach { year ->
                val yearAssets = groupedAssets[year]
                if (!yearAssets.isNullOrEmpty()) {
                    val header = HeaderItem(year.toLong(), year.toString())
                    val listRowAdapter = ArrayObjectAdapter(cardPresenter)
                    yearAssets.forEach { asset ->
                        listRowAdapter.add(asset.toCard(videoPlaybackPositions[asset.id]))
                    }
                    rowsAdapter.add(ListRow(header, listRowAdapter))
                }
            }

            Timber.d("Grouped ${assets.size} videos into ${years.size} years with ${recentAssets.size} recent videos")
        }
    }

    private fun onItemClicked(card: Card) {
        if (sliderItems.isEmpty() || !isAdded) {
            return
        }

        val selectedIndex = sliderItems.indexOfFirst { it.ids().contains(card.id) }
        if (selectedIndex == -1) {
            return
        }

        findNavController().navigate(
            AlbumDetailsFragmentDirections.actionToPhotoSlider(
                MediaSliderConfiguration(
                    selectedIndex,
                    PreferenceManager.get(SLIDER_INTERVAL),
                    PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
                    isVideoSoundEnable = true,
                    sliderItems,
                    null,
                    { },
                    animationSpeedMillis = PreferenceManager.get(SLIDER_ANIMATION_SPEED),
                    maxCutOffHeight = PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
                    maxCutOffWidth = PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
                    transformation = PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
                    debugEnabled = PreferenceManager.get(DEBUG_MODE),
                    enableSlideAnimation = PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
                    gradiantOverlay = false,
                    metaDataConfig = emptyList(),
                    useVideoDpadSeekControls = true
                )
            )
        )
    }

    private class VideosCardPresenter(context: Context) : CardPresenter(context) {
        override fun onBindViewHolder(card: ICard, cardView: ImageCardView) {
            super.onBindViewHolder(card, cardView)
            if (card is Card && card.isVideo) {
                val videoTitle = card.description?.trim().orEmpty()
                if (videoTitle.isNotEmpty()) {
                    cardView.cardType = BaseCardView.CARD_TYPE_INFO_UNDER
                    cardView.titleText = videoTitle
                    cardView.contentText = null
                    styleVideoTitle(cardView)
                } else {
                    cardView.cardType = BaseCardView.CARD_TYPE_MAIN_ONLY
                    cardView.titleText = null
                    cardView.contentText = null
                }
            }

            applyVideoForeground(card, cardView)
        }

        private fun styleVideoTitle(cardView: ImageCardView) {
            cardView.setInfoAreaBackgroundColor(Color.BLACK)
            cardView.findViewById<TextView>(androidx.leanback.R.id.title_text)?.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.video_card_title_text_size))
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setSingleLine(false)
                minLines = 1
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                }
            }
            cardView.findViewById<TextView>(androidx.leanback.R.id.content_text)?.visibility = View.GONE
        }

        private fun applyVideoForeground(card: ICard, cardView: ImageCardView) {
            val foregroundLayers = mutableListOf<Drawable>()
            cardView.mainImageView?.foreground?.let { foregroundLayers.add(it) }

            if (card is Card) {
                getPlaybackProgressPercent(card)?.let { progressPercent ->
                    foregroundLayers.add(
                        VideoProgressDrawable(
                            progressPercent = progressPercent,
                            trackColor = Color.argb(120, 0, 0, 0),
                            progressColor = Color.WHITE,
                            heightPx = cardView.resources.getDimensionPixelSize(R.dimen.video_thumbnail_progress_height),
                            horizontalInsetPx = cardView.resources.getDimensionPixelSize(R.dimen.video_thumbnail_progress_horizontal_inset),
                            bottomInsetPx = cardView.resources.getDimensionPixelSize(R.dimen.video_thumbnail_progress_bottom_inset),
                            cornerRadiusPx = cardView.resources.getDimension(R.dimen.video_thumbnail_progress_corner_radius)
                        )
                    )
                }
            }

            cardView.context.getDrawable(R.drawable.bg_video_card_focus)?.let { foregroundLayers.add(it) }
            cardView.mainImageView?.foreground = when (foregroundLayers.size) {
                0 -> null
                1 -> foregroundLayers.first()
                else -> LayerDrawable(foregroundLayers.toTypedArray())
            }
        }

        private fun getPlaybackProgressPercent(card: Card): Float? {
            val positionSeconds = card.videoPlaybackPositionSeconds?.takeIf { it > 0 } ?: return null
            val durationSeconds = parseDurationSeconds(card.videoDuration) ?: return null
            if (durationSeconds <= 0) {
                return null
            }

            return ((positionSeconds.toFloat() / durationSeconds) * 100f).coerceIn(0f, 100f)
        }

        private fun parseDurationSeconds(duration: String?): Int? {
            val text = duration?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val seconds = text.split(":").fold(0.0) { total, part ->
                val parsedPart = part.toDoubleOrNull() ?: return null
                (total * 60) + parsedPart
            }

            return seconds.toInt().takeIf { it > 0 }
        }
    }

    private class VideoProgressDrawable(
        private val progressPercent: Float,
        private val trackColor: Int,
        private val progressColor: Int,
        private val heightPx: Int,
        private val horizontalInsetPx: Int,
        private val bottomInsetPx: Int,
        private val cornerRadiusPx: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val width = bounds.width() - (horizontalInsetPx * 2)
            if (width <= 0 || heightPx <= 0) {
                return
            }

            val left = bounds.left + horizontalInsetPx.toFloat()
            val right = bounds.right - horizontalInsetPx.toFloat()
            val bottom = bounds.bottom - bottomInsetPx.toFloat()
            val top = bottom - heightPx
            rect.set(left, top, right, bottom)

            paint.color = trackColor
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)

            rect.right = left + (width * (progressPercent / 100f))
            paint.color = progressColor
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int {
            return PixelFormat.TRANSLUCENT
        }
    }

    private class VideosListRowPresenter : ListRowPresenter() {
        override fun initializeRowViewHolder(holder: RowPresenter.ViewHolder) {
            super.initializeRowViewHolder(holder)

            (holder as? ViewHolder)?.gridView?.apply {
                setFocusScrollStrategy(BaseGridView.FOCUS_SCROLL_ALIGNED)
                setWindowAlignment(BaseGridView.WINDOW_ALIGN_NO_EDGE)
                setWindowAlignmentOffset(resources.getDimensionPixelSize(R.dimen.video_row_start_offset))
                setWindowAlignmentOffsetPercent(BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED)
                setItemAlignmentOffset(0)
                setItemAlignmentOffsetPercent(BaseGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED)
            }
        }

        override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
            super.onBindRowViewHolder(holder, item)

            styleHeader(holder)
        }

        private fun styleHeader(holder: RowPresenter.ViewHolder) {
            val headerContainer = holder.headerViewHolder?.view ?: return
            headerContainer.translationX = -headerContainer.resources.getDimension(R.dimen.video_row_header_offset_start)
            headerContainer.layoutParams = headerContainer.layoutParams.apply {
                if (this is ViewGroup.MarginLayoutParams) {
                    bottomMargin = headerContainer.resources.getDimensionPixelSize(R.dimen.video_row_header_margin_bottom)
                }
            }

            headerContainer.findViewById<TextView>(androidx.leanback.R.id.row_header)?.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.video_row_header_text_size))
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }
        }
    }

    companion object {
        fun clearCache() {
            VideosCache.clear()
        }

        private const val PAGE_SIZE = 200
        private const val CONTINUE_WATCHING_HEADER_ID = -1L
    }
}
