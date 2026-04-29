package nl.giejay.android.tv.immich.assets

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import androidx.leanback.widget.VerticalGridView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Bucket
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.shared.cache.FavoriteCache
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.GRID_COLUMN_COUNT
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.MetaDataScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ANIMATE_ASSET_SLIDE
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ANIMATION_SPEED
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_GLIDE_TRANSFORMATION
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_HEIGHT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_WIDTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ONLY_USE_THUMBNAILS
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.util.LoadMore
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class IntegratedTimelineFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    companion object {
        private const val MONTH_SELECTION_DEBOUNCE_MS = 180L
        private const val INITIAL_LOAD_RETRY_DELAY_MS = 600L
        private const val INITIAL_LOAD_MAX_RETRIES = 4
        private const val LOAD_MORE_THRESHOLD = 12
        private const val SIDEBAR_FOCUS_OFFSET = 96
        private const val DEFAULT_GRID_COLUMN_COUNT = 4
    }

    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)
    private val viewModel: TimelineViewModel by viewModels()

    private lateinit var apiClient: ApiClient
    private lateinit var sidebarAdapter: ArrayObjectAdapter
    private lateinit var assetAdapter: ArrayObjectAdapter
    private lateinit var titleSelection: TextView
    private lateinit var assetCount: TextView
    private lateinit var loader: ProgressBar
    private lateinit var sidebarRecyclerView: RecyclerView
    private lateinit var gridAssets: VerticalGridView

    private var sidebarItems: List<AssetsSidebarItem> = emptyList()
    private var currentAssetList: List<Asset> = emptyList()
    private var renderedBucketId: String? = null
    private var pendingGridFocusBucketId: String? = null
    private var pendingGridRestoreAssetId: String? = null
    private var pendingSidebarReentryBucketId: String? = null
    private var initialSidebarFocusApplied = false
    private var monthSelectionJob: Job? = null
    private var initialLoadRetryJob: Job? = null
    private var initialLoadRetryCount = 0
    private var bucketsLoading = false
    private var assetsLoading = false
    private var gridColumnCount = DEFAULT_GRID_COLUMN_COUNT

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<IntegratedTimelineFragment> {
        return mMainFragmentAdapter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_timeline_integrated, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        titleSelection = view.findViewById(R.id.lbl_current_selection)
        assetCount = view.findViewById(R.id.lbl_asset_count)
        loader = view.findViewById(R.id.loading_indicator)
        sidebarRecyclerView = view.findViewById(R.id.sidebar_navigation_recycler_view)
        gridAssets = view.findViewById(R.id.grid_assets)
        gridColumnCount = resolveGridColumnCount()

        setupApiClient()
        setupGrids()
        observeViewModel()
        viewModel.loadBuckets(apiClient)
    }

    override fun onResume() {
        super.onResume()

        refreshGridColumnsIfNeeded()
        retryInitialBucketLoadIfNeeded()

        if (pendingGridRestoreAssetId != null && currentAssetList.isNotEmpty()) {
            gridAssets.post {
                restoreGridFocusIfPending(currentAssetList)
            }
        }
    }

    override fun onPause() {
        pendingSidebarReentryBucketId = null
        super.onPause()
    }

    override fun onDestroyView() {
        monthSelectionJob?.cancel()
        initialLoadRetryJob?.cancel()
        currentAssetList = emptyList()
        renderedBucketId = null
        pendingGridFocusBucketId = null
        super.onDestroyView()
    }

    private fun setupApiClient() {
        apiClient = ApiClient.getClient(
            ApiClientConfig(
                PreferenceManager.get(HOST_NAME),
                PreferenceManager.get(API_KEY),
                PreferenceManager.get(DISABLE_SSL_VERIFICATION),
                PreferenceManager.get(DEBUG_MODE)
            )
        )
    }

    private fun retryInitialBucketLoadIfNeeded() {
        if (viewModel.buckets.value.isNotEmpty() || initialLoadRetryJob?.isActive == true) {
            return
        }

        initialLoadRetryJob = viewLifecycleOwner.lifecycleScope.launch {
            while (
                viewModel.buckets.value.isEmpty() &&
                initialLoadRetryCount < INITIAL_LOAD_MAX_RETRIES
            ) {
                delay(INITIAL_LOAD_RETRY_DELAY_MS)
                if (viewModel.buckets.value.isNotEmpty() || bucketsLoading) {
                    continue
                }

                initialLoadRetryCount += 1
                setupApiClient()
                viewModel.loadBuckets(apiClient, forceReload = true)
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.buckets.collect { buckets ->
                        if (buckets.isNotEmpty()) {
                            updateSidebar(buckets)
                            applyInitialSidebarFocus()
                        }
                    }
                }

                launch {
                    viewModel.selectedBucketId.collect { selectedId ->
                        if (selectedId != null) {
                            updateSidebarSelection(selectedId)
                            viewModel.getSelectedBucket()?.let { updateTitle(it) }
                        }
                    }
                }

                launch {
                    viewModel.assets.collect { assets ->
                        renderGrid(assets)
                    }
                }

                launch {
                    viewModel.isLoading.collect { isLoading ->
                        bucketsLoading = isLoading
                        updateLoaderVisibility()
                    }
                }

                launch {
                    viewModel.isLoadingAssets.collect { isLoading ->
                        assetsLoading = isLoading
                        updateLoaderVisibility()
                    }
                }
            }
        }
    }

    private fun setupGrids() {
        val sidebarPresenterSelector = object : PresenterSelector() {
            private val yearPresenter = SidebarYearPresenter()
            private val monthPresenter = SidebarMonthPresenter()

            override fun getPresenter(item: Any?): Presenter? {
                return when (item) {
                    is AssetsSidebarItem.YearItem -> yearPresenter
                    is AssetsSidebarItem.MonthItem -> monthPresenter
                    else -> null
                }
            }
        }

        sidebarAdapter = ArrayObjectAdapter(sidebarPresenterSelector)
        sidebarRecyclerView.adapter = ItemBridgeAdapter(sidebarAdapter)
        sidebarRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        sidebarRecyclerView.itemAnimator = null

        assetAdapter = ArrayObjectAdapter(SimpleAssetPresenter())
        gridAssets.setNumColumns(gridColumnCount)
        gridAssets.setItemSpacing(16)
        gridAssets.adapter = ItemBridgeAdapter(assetAdapter)
        gridAssets.isFocusable = true
        gridAssets.isFocusableInTouchMode = false
        gridAssets.onFocusChangeListener = View.OnFocusChangeListener { _, _ ->
            refreshVisibleSidebarMonthViews()
        }

        gridAssets.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                val position = gridAssets.selectedPosition
                if (position != -1 && position % gridColumnCount == 0) {
                    viewModel.selectedBucketId.value?.let { scrollToAndFocusBucket(it) }
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    private fun resolveGridColumnCount(): Int {
        return try {
            PreferenceManager.get(GRID_COLUMN_COUNT)
        } catch (_: Exception) {
            DEFAULT_GRID_COLUMN_COUNT
        }
    }

    private fun refreshGridColumnsIfNeeded() {
        val preferredColumnCount = resolveGridColumnCount()
        if (preferredColumnCount == gridColumnCount) {
            return
        }

        gridColumnCount = preferredColumnCount
        gridAssets.setNumColumns(gridColumnCount)

        if (currentAssetList.isNotEmpty()) {
            renderedBucketId = null
            renderGrid(currentAssetList)
        }
    }

    private fun updateSidebar(buckets: List<Bucket>) {
        val selectedId = viewModel.selectedBucketId.value
        val bucketsByYear = buckets.groupBy { bucket ->
            try {
                bucket.timeBucket.substring(0, 4)
            } catch (_: Exception) {
                "Unknown"
            }
        }

        val newItems = mutableListOf<AssetsSidebarItem>()
        bucketsByYear.keys.sortedDescending().forEach { year ->
            newItems.add(AssetsSidebarItem.YearItem(year))
            bucketsByYear[year]?.forEach { bucket ->
                newItems.add(AssetsSidebarItem.MonthItem(bucket, isSelected = bucket.timeBucket == selectedId))
            }
        }

        sidebarItems = newItems
        sidebarAdapter.clear()
        sidebarAdapter.addAll(0, sidebarItems)
    }

    private fun updateSidebarSelection(selectedId: String) {
        if (sidebarItems.isEmpty()) {
            return
        }

        val newItems = sidebarItems.map { item ->
            if (item is AssetsSidebarItem.MonthItem) {
                item.copy(isSelected = item.bucket.timeBucket == selectedId)
            } else {
                item
            }
        }

        for (index in sidebarItems.indices) {
            if (sidebarItems[index] != newItems[index]) {
                sidebarAdapter.replace(index, newItems[index])
            }
        }

        sidebarItems = newItems
    }

    private fun applyInitialSidebarFocus() {
        if (initialSidebarFocusApplied) {
            return
        }
        val selectedId = viewModel.selectedBucketId.value ?: return
        initialSidebarFocusApplied = true
        scrollToAndFocusBucket(selectedId)
    }

    private fun scrollToAndFocusBucket(selectedId: String) {
        val index = sidebarItems.indexOfFirst {
            it is AssetsSidebarItem.MonthItem && it.bucket.timeBucket == selectedId
        }
        if (index == -1) {
            return
        }
        focusSidebarIndex(index)
    }

    private fun updateTitle(bucket: Bucket) {
        val prettyDate = try {
            LocalDate.parse(bucket.timeBucket)
                .format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        } catch (_: Exception) {
            bucket.timeBucket
        }

        titleSelection.text = prettyDate.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        assetCount.text = "(${bucket.count} items)"
    }

    private fun updateLoaderVisibility() {
        loader.visibility = if (bucketsLoading || assetsLoading) View.VISIBLE else View.GONE
    }

    private fun selectBucket(bucket: Bucket, immediate: Boolean) {
        monthSelectionJob?.cancel()

        if (pendingGridRestoreAssetId != null && viewModel.selectedBucketId.value != bucket.timeBucket) {
            pendingGridRestoreAssetId = null
        }

        if (!immediate && viewModel.selectedBucketId.value == bucket.timeBucket) {
            return
        }

        if (immediate) {
            viewModel.selectBucket(bucket.timeBucket, apiClient)
            return
        }

        monthSelectionJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(MONTH_SELECTION_DEBOUNCE_MS)
            viewModel.selectBucket(bucket.timeBucket, apiClient)
        }
    }

    private fun focusSidebarIndex(index: Int) {
        val layoutManager = sidebarRecyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        sidebarRecyclerView.post {
            layoutManager.scrollToPositionWithOffset(index, SIDEBAR_FOCUS_OFFSET)
            requestSidebarFocus(index)
        }
    }

    private fun requestSidebarFocus(index: Int, attempt: Int = 0) {
        val viewHolder = sidebarRecyclerView.findViewHolderForAdapterPosition(index)
        if (viewHolder != null) {
            viewHolder.itemView.requestFocus()
            return
        }

        if (attempt >= 4) {
            return
        }

        sidebarRecyclerView.postDelayed({
            requestSidebarFocus(index, attempt + 1)
        }, 16L)
    }

    private fun findMonthIndex(fromIndex: Int, step: Int): Int {
        var index = fromIndex + step
        while (index in sidebarItems.indices) {
            if (sidebarItems[index] is AssetsSidebarItem.MonthItem) {
                return index
            }
            index += step
        }
        return -1
    }

    private fun refreshVisibleSidebarMonthViews() {
        for (childIndex in 0 until sidebarRecyclerView.childCount) {
            val child = sidebarRecyclerView.getChildAt(childIndex)
            val adapterIndex = sidebarRecyclerView.getChildAdapterPosition(child)
            val item = sidebarItems.getOrNull(adapterIndex)
            if (item is AssetsSidebarItem.MonthItem) {
                bindMonthView(child, item.bucket)
            }
        }
    }

    private fun bindMonthView(view: View, bucket: Bucket) {
        val monthNameView = view.findViewById<TextView>(R.id.month_name)
        val countView = view.findViewById<TextView>(R.id.month_count)
        val context = view.context

        val monthName = try {
            LocalDate.parse(bucket.timeBucket)
                .format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()))
        } catch (_: Exception) {
            bucket.timeBucket
        }

        monthNameView.text = monthName.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        countView.text = bucket.count.toString()

        val isSelected = viewModel.selectedBucketId.value == bucket.timeBucket
        val isHighlighted = view.hasFocus() || (isSelected && !sidebarRecyclerView.hasFocus())

        if (isHighlighted) {
            view.setBackgroundColor(context.getColor(R.color.selected_month_background))
            monthNameView.setTextColor(context.getColor(R.color.selected_text_color))
            countView.setTextColor(context.getColor(R.color.selected_text_color))
        } else {
            view.setBackgroundColor(context.getColor(android.R.color.transparent))
            monthNameView.setTextColor(context.getColor(R.color.unselected_text_color))
            countView.setTextColor(context.getColor(R.color.unselected_text_color))
        }
    }

    private fun renderGrid(assets: List<Asset>) {
        val selectedBucketId = viewModel.selectedBucketId.value
        val previousAssets = currentAssetList
        val previousBucketId = renderedBucketId
        val cards = applyFavoriteOverrides(assets).map { asset ->
            Card(
                id = asset.id,
                title = "",
                description = "",
                thumbnailUrl = ApiUtil.getThumbnailUrl(asset.id, "thumbnail", asset.thumbhash),
                backgroundUrl = ApiUtil.getThumbnailUrl(asset.id, "preview", asset.thumbhash),
                isVideo = asset.type == "VIDEO",
                isFavorite = asset.isFavorite
            )
        }

        val assetIds = assets.map { it.id }
        val previousIds = previousAssets.map { it.id }
        val shouldAppend = previousBucketId == selectedBucketId &&
            previousAssets.isNotEmpty() &&
            assetIds.size > previousIds.size &&
            assetIds.take(previousIds.size) == previousIds
        val sameContent = previousBucketId == selectedBucketId && assetIds == previousIds

        if (!sameContent) {
            if (shouldAppend) {
                val newCards = cards.drop(previousAssets.size)
                if (newCards.isNotEmpty()) {
                    assetAdapter.addAll(assetAdapter.size(), newCards)
                }
            } else {
                assetAdapter.clear()
                if (cards.isNotEmpty()) {
                    assetAdapter.addAll(0, cards)
                    gridAssets.scrollToPosition(0)
                }
            }
        }

        renderedBucketId = selectedBucketId
        currentAssetList = assets
        restoreGridFocusIfPending(assets)
        focusGridIfPending(selectedBucketId, assets)
    }

    private fun focusGridIfPending(selectedBucketId: String?, assets: List<Asset>) {
        if (pendingGridFocusBucketId == null || pendingGridFocusBucketId != selectedBucketId || assets.isEmpty()) {
            return
        }
        pendingGridFocusBucketId = null
        gridAssets.post { gridAssets.requestFocus() }
    }

    private fun restoreGridFocusIfPending(assets: List<Asset>) {
        val assetId = pendingGridRestoreAssetId ?: return
        val index = assets.indexOfFirst { it.id == assetId }
        if (index == -1) {
            return
        }

        focusGridAsset(index)
    }

    private fun focusGridAsset(index: Int) {
        gridAssets.post {
            gridAssets.requestFocus()
            gridAssets.selectedPosition = index
            gridAssets.scrollToPosition(index)
            requestGridFocus(index)
        }
    }

    private fun requestGridFocus(index: Int, attempt: Int = 0) {
        val viewHolder = gridAssets.findViewHolderForAdapterPosition(index)
        if (viewHolder != null) {
            gridAssets.requestFocus()
            if (viewHolder.itemView.requestFocus()) {
                pendingGridRestoreAssetId = null
                return
            }
        }

        if (attempt >= 10) {
            return
        }

        gridAssets.postDelayed({
            gridAssets.requestFocus()
            gridAssets.selectedPosition = index
            requestGridFocus(index, attempt + 1)
        }, 32L)
    }

    private fun maybeLoadNextPage(selectedPosition: Int) {
        if (selectedPosition == RecyclerView.NO_POSITION) {
            return
        }
        if (assetAdapter.size() - selectedPosition <= LOAD_MORE_THRESHOLD) {
            viewModel.maybeLoadMoreForSelectedBucket(apiClient)
        }
    }

    private fun applyFavoriteOverrides(assets: List<Asset>): List<Asset> {
        return assets.map { asset ->
            FavoriteCache.overrides[asset.id]?.let { override ->
                asset.copy(isFavorite = override)
            } ?: asset
        }
    }

    private fun openPhotoSlider(card: Card) {
        val syncedAssets = applyFavoriteOverrides(currentAssetList)
        if (syncedAssets.isEmpty()) {
            return
        }

        pendingGridRestoreAssetId = card.id

        val sliderItems = syncedAssets.toSliderItems(keepOrder = true, mergePortrait = false)
        val sliderStartIndex = sliderItems.indexOfFirst { it.ids().contains(card.id) }.let { index ->
            if (index == -1) 0 else index
        }

        val loadMore: LoadMore = suspend {
            val moreAssets = applyFavoriteOverrides(viewModel.loadMoreForSelectedBucket(apiClient))
            moreAssets.toSliderItems(true, false)
        }

        val config = MediaSliderConfiguration(
            sliderStartIndex,
            PreferenceManager.get(SLIDER_INTERVAL),
            PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
            isVideoSoundEnable = true,
            sliderItems,
            loadMore,
            { },
            PreferenceManager.get(SLIDER_ANIMATION_SPEED),
            PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
            PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
            PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
            PreferenceManager.get(DEBUG_MODE),
            gradiantOverlay = false,
            PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
            PreferenceManager.getAllMetaData(MetaDataScreen.VIEWER)
        )

        val bundle = Bundle()
        bundle.putParcelable("config", config)

        try {
            findNavController().navigate(R.id.action_homeFragment_to_photo_slider, bundle)
        } catch (e: Exception) {
            Timber.e(e, "Error opening photo slider via HomeFragment action")
            try {
                findNavController().navigate(R.id.action_timeline_to_photo_slider, bundle)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "Error navigating to viewer", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class SidebarYearPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline_year, parent, false)
            view.isFocusable = false
            return ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val yearItem = item as AssetsSidebarItem.YearItem
            viewHolder.view.findViewById<TextView>(R.id.year_title)?.text = yearItem.year
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
    }

    inner class SidebarMonthPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline_month, parent, false)
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            return ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val monthItem = item as AssetsSidebarItem.MonthItem
            val view = viewHolder.view

            view.setOnClickListener {
                selectBucket(monthItem.bucket, immediate = true)
            }

            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }

                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        pendingSidebarReentryBucketId = monthItem.bucket.timeBucket
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val currentIndex = sidebarRecyclerView.getChildAdapterPosition(view)
                        val previousIndex = findMonthIndex(currentIndex, -1)
                        if (previousIndex != -1) {
                            val previousViewHolder = sidebarRecyclerView.findViewHolderForAdapterPosition(previousIndex)
                            if (previousViewHolder == null) {
                                return@setOnKeyListener true
                            }
                            focusSidebarIndex(previousIndex)
                            return@setOnKeyListener true
                        }
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val currentIndex = sidebarRecyclerView.getChildAdapterPosition(view)
                        val nextIndex = findMonthIndex(currentIndex, 1)
                        if (nextIndex != -1) {
                            focusSidebarIndex(nextIndex)
                            return@setOnKeyListener true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (viewModel.selectedBucketId.value != monthItem.bucket.timeBucket) {
                            pendingGridFocusBucketId = monthItem.bucket.timeBucket
                            selectBucket(monthItem.bucket, immediate = true)
                            return@setOnKeyListener true
                        }

                        if (assetAdapter.size() > 0) {
                            gridAssets.requestFocus()
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }

            view.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pendingBucketId = pendingSidebarReentryBucketId
                    if (pendingBucketId != null && pendingBucketId != monthItem.bucket.timeBucket) {
                        scrollToAndFocusBucket(pendingBucketId)
                        return@setOnFocusChangeListener
                    }
                    pendingSidebarReentryBucketId = null
                    selectBucket(monthItem.bucket, immediate = false)
                }
                bindMonthView(view, monthItem.bucket)
                view.post { refreshVisibleSidebarMonthViews() }
            }

            bindMonthView(view, monthItem.bucket)
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            viewHolder.view.setOnClickListener(null)
            viewHolder.view.setOnKeyListener(null)
            viewHolder.view.onFocusChangeListener = null
        }

    }

    inner class SimpleAssetPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline_card_new, parent, false)
            applyTimelineCardDimensions(view)
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            (parent as? ViewGroup)?.clipChildren = false
            (parent as? ViewGroup)?.clipToPadding = false
            return ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val card = item as Card
            val view = viewHolder.view
            val imageView = view.findViewById<ImageView>(R.id.image_view)
            val favoriteIcon = view.findViewById<ImageView>(R.id.icon_favorite)
            val videoIcon = view.findViewById<ImageView>(R.id.icon_video)
            applyTimelineCardDimensions(view)

            Glide.with(view.context)
                .load(card.thumbnailUrl)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(imageView)

            favoriteIcon.visibility = if (card.isFavorite) View.VISIBLE else View.GONE
            videoIcon.visibility = if (card.isVideo) View.VISIBLE else View.GONE

            view.setOnClickListener {
                openPhotoSlider(card)
            }

            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    openPhotoSlider(card)
                    return@setOnKeyListener true
                }
                false
            }

            view.setOnFocusChangeListener { focusedView, hasFocus ->
                if (hasFocus) {
                    maybeLoadNextPage(gridAssets.selectedPosition)
                    focusedView.animate().scaleX(1.1f).scaleY(1.1f).duration = 150
                    focusedView.elevation = 10f
                    focusedView.isSelected = true
                } else {
                    focusedView.animate().scaleX(1.0f).scaleY(1.0f).duration = 150
                    focusedView.elevation = 2f
                    focusedView.isSelected = false
                }
            }
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {
            val imageView = viewHolder.view.findViewById<ImageView>(R.id.image_view)
            Glide.with(viewHolder.view.context).clear(imageView)
            viewHolder.view.setOnClickListener(null)
            viewHolder.view.setOnKeyListener(null)
            viewHolder.view.onFocusChangeListener = null
        }

        private fun applyTimelineCardDimensions(view: View) {
            val imageView = view.findViewById<ImageView>(R.id.image_view)
            val (widthRes, heightRes) = when (resolveGridColumnCount()) {
                3 -> Pair(R.dimen.card_width_3_cols, R.dimen.card_height_3_cols)
                5 -> Pair(R.dimen.card_width_5_cols, R.dimen.card_height_5_cols)
                else -> Pair(R.dimen.card_width_4_cols, R.dimen.card_height_4_cols)
            }

            val width = view.resources.getDimensionPixelSize(widthRes)
            val height = view.resources.getDimensionPixelSize(heightRes)
            val layoutParams = imageView.layoutParams
            if (layoutParams.width != width || layoutParams.height != height) {
                layoutParams.width = width
                layoutParams.height = height
                imageView.layoutParams = layoutParams
            }
        }
    }
}
