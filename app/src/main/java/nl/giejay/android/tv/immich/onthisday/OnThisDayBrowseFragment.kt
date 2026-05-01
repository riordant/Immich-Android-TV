package nl.giejay.android.tv.immich.onthisday

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.album.AlbumDetailsFragmentDirections
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.card.CardPresenter
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.FILTER_CONTENT_TYPE
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.MetaDataScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ANIMATE_ASSET_SLIDE
import nl.giejay.android.tv.immich.shared.prefs.SIMILAR_ASSETS_YEARS_BACK
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
import java.time.LocalDate
import java.util.Calendar
import java.util.Date

private data class OnThisDayCacheKey(
    val hostName: String,
    val contentType: ContentType,
    val date: LocalDate
)

private data class OnThisDayCacheValue(
    val assets: List<Asset>,
    val sliderItems: List<SliderItemViewHolder>,
    val yearsBack: Int
)

private object OnThisDayCache {
    private var key: OnThisDayCacheKey? = null
    private var value: OnThisDayCacheValue? = null

    fun get(requestKey: OnThisDayCacheKey): OnThisDayCacheValue? {
        return if (key == requestKey) value else null
    }

    fun put(requestKey: OnThisDayCacheKey, cacheValue: OnThisDayCacheValue) {
        key = requestKey
        value = cacheValue
    }

    fun clear() {
        key = null
        value = null
    }
}

class OnThisDayBrowseFragment : RowsSupportFragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    private val ioScope = CoroutineScope(Job() + Dispatchers.IO)
    private lateinit var apiClient: ApiClient
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private var loadingIndicator: ProgressBar? = null
    private var sliderItems: List<SliderItemViewHolder> = emptyList()

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

        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter

        setupClient()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        showLoading(true)
        loadOnThisDayAssets()
    }

    override fun onDestroyView() {
        loadingIndicator = null
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

    private fun cacheKey(): OnThisDayCacheKey {
        return OnThisDayCacheKey(
            hostName = PreferenceManager.get(HOST_NAME),
            contentType = PreferenceManager.get(FILTER_CONTENT_TYPE),
            date = LocalDate.now()
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

    private fun loadOnThisDayAssets() {
        val requestKey = cacheKey()
        OnThisDayCache.get(requestKey)?.let { cached ->
            sliderItems = cached.sliderItems
            renderRows(cached.assets, cached.yearsBack)
            showLoading(false)
            return
        }

        ioScope.launch {
            try {
                val oldestAssetResult = apiClient.getOldestAsset()
                oldestAssetResult.fold(
                    { error ->
                        Timber.e("Error loading oldest asset: $error")
                        loadAssetsWithYearsBack(PreferenceManager.get(SIMILAR_ASSETS_YEARS_BACK), requestKey)
                    },
                    { oldestAsset ->
                        val oldestAssetDate = getDateFromAsset(oldestAsset)
                        val oldestYear = if (oldestAssetDate != null) {
                            Calendar.getInstance().apply { time = oldestAssetDate }.get(Calendar.YEAR)
                        } else {
                            Calendar.getInstance().get(Calendar.YEAR) - PreferenceManager.get(SIMILAR_ASSETS_YEARS_BACK)
                        }
                        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                        loadAssetsWithYearsBack(currentYear - oldestYear, requestKey)
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "Exception in OnThisDayBrowseFragment")
                showLoading(false)
            }
        }
    }

    private suspend fun loadAssetsWithYearsBack(yearsBack: Int, requestKey: OnThisDayCacheKey) {
        val result = apiClient.onThisDayAssets(
            1,
            1000,
            PreferenceManager.get(FILTER_CONTENT_TYPE),
            yearsBack
        )

        result.fold(
            { error ->
                Timber.e("Error loading 'On this day' assets: $error")
                showLoading(false)
            },
            { assets ->
                Timber.d("Loaded ${assets.size} assets for 'On this day'")
                sliderItems = assets.toSliderItems(keepOrder = true, mergePortrait = false)
                OnThisDayCache.put(requestKey, OnThisDayCacheValue(assets, sliderItems, yearsBack))
                renderRows(assets, yearsBack)
                showLoading(false)
            }
        )
    }

    private fun renderRows(assets: List<Asset>, yearsBack: Int) {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val groupedAssets = assets.groupBy {
            getDateFromAsset(it)?.let { date ->
                Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
            }
        }

        if (!isAdded) {
            return
        }

        requireActivity().runOnUiThread {
            rowsAdapter.clear()
            val cardPresenter = CardPresenter(requireContext())
            cardPresenter.onClick = { clickedCard ->
                if (clickedCard is Card) {
                    onItemClicked(clickedCard)
                }
            }

            (0..yearsBack).forEach { yearOffset ->
                val year = currentYear - yearOffset
                val yearAssets = groupedAssets[year]
                if (!yearAssets.isNullOrEmpty()) {
                    val header = HeaderItem(year.toLong(), year.toString())
                    val listRowAdapter = ArrayObjectAdapter(cardPresenter)
                    yearAssets.forEach { asset ->
                        listRowAdapter.add(asset.toCard())
                    }
                    rowsAdapter.add(ListRow(header, listRowAdapter))
                }
            }

            Timber.d("Grouped into ${rowsAdapter.size()} years")
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
                    metaDataConfig = PreferenceManager.getAllMetaData(MetaDataScreen.VIEWER)
                )
            )
        )
    }

    companion object {
        fun clearCache() {
            OnThisDayCache.clear()
        }
    }
}
