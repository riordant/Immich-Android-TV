package nl.giejay.android.tv.immich.home

import android.content.SharedPreferences
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.BrowseSupportFragment.BrowseTransitionListener
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DividerPresenter
import androidx.leanback.widget.DividerRow
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.PageRow
import androidx.leanback.widget.PresenterSelector
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowHeaderPresenter
import androidx.leanback.widget.SectionRow
import nl.giejay.android.tv.immich.MainActivity
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.album.AlbumFragment
import nl.giejay.android.tv.immich.assets.AllAssetFragment
import nl.giejay.android.tv.immich.assets.FolderFragment
import nl.giejay.android.tv.immich.assets.RandomAssetsFragment
import nl.giejay.android.tv.immich.assets.RecentAssetsFragment
import nl.giejay.android.tv.immich.assets.SimilarTimeAssetsFragment
import nl.giejay.android.tv.immich.assets.TimelineFragment
import nl.giejay.android.tv.immich.onthisday.OnThisDayBrowseFragment
import nl.giejay.android.tv.immich.favorite.FavoritesBrowseFragment
import nl.giejay.android.tv.immich.people.PeopleFragment
import nl.giejay.android.tv.immich.settings.SettingsFragment
import nl.giejay.android.tv.immich.shared.prefs.HIDDEN_HOME_ITEMS
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SHOW_FOLDERS_IN_MAIN_COLUMN
import nl.giejay.android.tv.immich.videos.VideosBrowseFragment
import timber.log.Timber

class HomeFragment : BrowseSupportFragment() {
    private lateinit var mRowsAdapter: ArrayObjectAdapter
    private lateinit var rows: List<PageRow>
    private var showingFolders = false
    private var dynamicTitle: String? = null
    private var hidingTitleForHeaderTransition = false
    private var sidebarControlsView: View? = null
    private var exitAppButton: View? = null
    private var refreshAppButton: View? = null
    private var headerRailConfigured = false
    private val showFoldersPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SHOW_FOLDERS_IN_MAIN_COLUMN.key() && ::mRowsAdapter.isInitialized) {
                view?.post { refreshRows(preserveSelectedHeader = true) }
            }
        }
    val immichRowPresenter = ImmichRowPresenter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.i("Loaded Home")
        PreferenceManager.sharedPreference.registerOnSharedPreferenceChangeListener(showFoldersPreferenceListener)

        mainFragmentRegistry.registerFragment(PageRow::class.java, PageRowFragmentFactory())
        setupUi()
        loadData()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        attachHomeSidebarControls()
        setupHeaderFocusBridge()

        headersSupportFragment.setOnHeaderViewSelectedListener { _, row ->
            val headerTitle = row?.headerItem?.name ?: "-"
            selectedPosition = row?.let { mRowsAdapter.indexOf(it) } ?: 0
            if (isShowingHeaders || isInHeadersTransition || dynamicTitle == null) {
                clearDynamicTitle(displayTitleForHeader(headerTitle))
            }
        }

        setBrowseTransitionListener(object : BrowseTransitionListener() {
            override fun onHeadersTransitionStart(withHeaders: Boolean) {
                if (withHeaders) {
                    clearDynamicTitle()
                }
            }

            override fun onHeadersTransitionStop(withHeaders: Boolean) {
                if (withHeaders) {
                    clearDynamicTitle()
                } else {
                    (getMainFragment() as? VideosBrowseFragment)?.publishSelectedTitleIfVisible()
                }
                setTitleTextVisible(true)
            }
        })

        headersSupportFragment.setOnHeaderClickedListener { _, row ->
            if (!this.isInHeadersTransition) {
                this.startHeadersTransition(false)
            }
        }

        view.post {
            if (::mRowsAdapter.isInitialized && mRowsAdapter.size() > 0) {
                selectedPosition = selectedPosition.coerceIn(0, mRowsAdapter.size() - 1)
                refreshTitleFromSelectedRow()
            }
        }
        view.postDelayed({ refreshTitleFromSelectedRow() }, INITIAL_TITLE_REFRESH_DELAY_MS)
    }

    override fun onDestroyView() {
        immichRowPresenter.onNavigateToSidebarControls = null
        detachHomeSidebarControls()
        super.onDestroyView()
    }

    override fun startHeadersTransition(withHeaders: Boolean) {
        if (!isInHeadersTransition && isShowingHeaders != withHeaders) {
            hideTitleForHeaderTransition()
        }
        super.startHeadersTransition(withHeaders)
    }

    private fun setupUi() {
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = resources.getColor(android.R.color.black)
        title = "Albums"

        val sHeaderPresenter: PresenterSelector = ClassPresenterSelector()
            .addClassPresenter(DividerRow::class.java, DividerPresenter())
            .addClassPresenter(
                SectionRow::class.java,
                RowHeaderPresenter()
            )
            .addClassPresenter(Row::class.java, immichRowPresenter)

        setHeaderPresenterSelector(sHeaderPresenter)
    }

    private fun attachHomeSidebarControls() {
        val headersRoot = view?.findViewById<RelativeLayout>(androidx.leanback.R.id.browse_headers_root)
        if (headersRoot == null) {
            view?.postDelayed({ attachHomeSidebarControls() }, HEADER_FOCUS_BRIDGE_RETRY_DELAY_MS)
            return
        }

        if (sidebarControlsView?.parent != null) {
            return
        }

        val controlsView = layoutInflater.inflate(R.layout.home_sidebar_controls, headersRoot, false)
        val controlsLayoutParams = RelativeLayout.LayoutParams(
            resources.getDimensionPixelSize(R.dimen.lb_browse_headers_width),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.home_sidebar_controls_margin_top)
        }

        headersRoot.addView(controlsView, controlsLayoutParams)
        sidebarControlsView = controlsView
        exitAppButton = controlsView.findViewById(R.id.home_exit_button)
        refreshAppButton = controlsView.findViewById(R.id.home_refresh_button)

        exitAppButton?.setOnClickListener { exitApp() }
        refreshAppButton?.setOnClickListener { refreshApp() }
        setupSidebarButtonFocus()
        controlsView.visibility = View.VISIBLE
        controlsView.alpha = 1f
    }

    private fun detachHomeSidebarControls() {
        val controlsView = sidebarControlsView ?: return
        (controlsView.parent as? ViewGroup)?.removeView(controlsView)
        sidebarControlsView = null
        exitAppButton = null
        refreshAppButton = null
    }

    private fun setupSidebarButtonFocus() {
        exitAppButton?.setOnKeyListener { _, keyCode, event ->
            if (event?.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    refreshAppButton?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    requestHeadersFocus()
                    true
                }
                else -> false
            }
        }

        refreshAppButton?.setOnKeyListener { _, keyCode, event ->
            if (event?.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    exitAppButton?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    requestHeadersFocus()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupHeaderFocusBridge() {
        val headerGridView = headersSupportFragment.verticalGridView
        if (headerGridView == null) {
            view?.postDelayed({ setupHeaderFocusBridge() }, HEADER_FOCUS_BRIDGE_RETRY_DELAY_MS)
            return
        }

        if (!headerRailConfigured) {
            headerGridView.clipToPadding = true
            headerGridView.setPadding(
                headerGridView.paddingLeft,
                resources.getDimensionPixelSize(R.dimen.home_sidebar_header_grid_padding_top),
                headerGridView.paddingRight,
                headerGridView.paddingBottom
            )
            headerRailConfigured = true
        }

        immichRowPresenter.onNavigateToSidebarControls = { row, keyCode ->
            if (
                row.headerItem?.name == getSelectedHeaderName() &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || selectedPosition == 0) &&
                isShowingHeaders &&
                !isInHeadersTransition &&
                sidebarControlsView?.visibility == View.VISIBLE
            ) {
                exitAppButton?.requestFocus()
                true
            } else {
                false
            }
        }

        headerGridView.setOnKeyListener { _, keyCode, event ->
            if (
                event?.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                selectedPosition == 0 &&
                isShowingHeaders &&
                !isInHeadersTransition &&
                sidebarControlsView?.visibility == View.VISIBLE
            ) {
                exitAppButton?.requestFocus()
                true
            } else {
                false
            }
        }
    }

    private fun exitApp() {
        requireActivity().finishAffinity()
    }

    private fun refreshApp() {
        VideosBrowseFragment.clearCache()
        OnThisDayBrowseFragment.clearCache()

        val refreshIntent = Intent(requireContext(), MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(refreshIntent)
        requireActivity().finish()
    }

    private fun loadData() {
        mRowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = mRowsAdapter
        refreshRows()
    }

    override fun onResume() {
        super.onResume()
        if (::mRowsAdapter.isInitialized && showingFolders != PreferenceManager.get(SHOW_FOLDERS_IN_MAIN_COLUMN)) {
            refreshRows(preserveSelectedHeader = true)
        }
    }

    private fun refreshRows(preserveSelectedHeader: Boolean = false) {
        val selectedHeaderName = if (preserveSelectedHeader) getSelectedHeaderName() else null
        val visibleRows = createRows().filter { !PreferenceManager.itemInStringSet(it.headerItem.name, HIDDEN_HOME_ITEMS) }
        rows = visibleRows

        if (mRowsAdapter.size() == 0) {
            mRowsAdapter.addAll(0, visibleRows)
        } else {
            applyFoldersRowChange(visibleRows)
        }

        val selectedIndex = selectedHeaderName?.let { headerName ->
            visibleRows.indexOfFirst { it.headerItem.name == headerName }.takeIf { it >= 0 }
        }
        if (selectedIndex != null) {
            selectedPosition = selectedIndex
            title = displayTitleForHeader(visibleRows[selectedIndex].headerItem.name)
        } else if (!preserveSelectedHeader && visibleRows.isNotEmpty()) {
            title = displayTitleForHeader(visibleRows[selectedPosition.coerceIn(0, visibleRows.size - 1)].headerItem.name)
        }
    }

    private fun refreshTitleFromSelectedRow() {
        getSelectedHeaderName()?.let { selectedHeaderName ->
            applyDynamicTitleStyle(dynamicTitle != null)
            title = dynamicTitle ?: displayTitleForHeader(selectedHeaderName)
        }
    }

    fun setDynamicTitle(title: String?) {
        dynamicTitle = title
        applyDynamicTitleStyle(title != null)
        this.title = title ?: getSelectedHeaderName()?.let { displayTitleForHeader(it) } ?: "-"
    }

    private fun clearDynamicTitle(fallbackTitle: String? = getSelectedHeaderName()) {
        dynamicTitle = null
        applyDynamicTitleStyle(false)
        title = fallbackTitle?.let { displayTitleForHeader(it) } ?: "-"
    }

    private fun displayTitleForHeader(headerTitle: String): String {
        return if (headerTitle == VIDEOS_HEADER_NAME) VIDEOS_DISPLAY_TITLE else headerTitle
    }

    private fun applyDynamicTitleStyle(enabled: Boolean) {
        val titleTextView = getTitleTextView()
        if (titleTextView == null) {
            view?.post { getTitleTextView()?.let { configureTitleTextStyle(it, enabled) } }
            return
        }

        configureTitleTextStyle(titleTextView, enabled)
    }

    private fun getTitleTextView(): TextView? {
        return getTitleView()?.findViewById(androidx.leanback.R.id.title_text)
    }

    private fun setTitleTextVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        hidingTitleForHeaderTransition = !visible
        val titleTextView = getTitleTextView()
        if (titleTextView == null) {
            view?.post { getTitleTextView()?.visibility = visibility }
            return
        }

        titleTextView.visibility = visibility
    }

    private fun hideTitleForHeaderTransition() {
        hidingTitleForHeaderTransition = true
        getTitleTextView()?.text = null
        setTitleTextVisible(false)
    }

    private fun configureTitleTextStyle(titleTextView: TextView, enabled: Boolean) {
        if (enabled) {
            titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.video_dynamic_title_text_size))
            titleTextView.setTextColor(resources.getColor(android.R.color.white, null))
            titleTextView.setTypeface(titleTextView.typeface, Typeface.BOLD)
            titleTextView.setSingleLine(true)
            titleTextView.maxLines = 1
            titleTextView.maxWidth = resources.getDimensionPixelSize(R.dimen.video_dynamic_title_max_width)
            titleTextView.ellipsize = TextUtils.TruncateAt.END
            titleTextView.marqueeRepeatLimit = 0
            titleTextView.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            titleTextView.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
            titleTextView.includeFontPadding = false
            titleTextView.isSelected = false
            titleTextView.setBackgroundResource(R.drawable.bg_video_dynamic_title)
            val horizontalPadding = resources.getDimensionPixelSize(R.dimen.video_dynamic_title_padding_horizontal)
            val verticalPadding = resources.getDimensionPixelSize(R.dimen.video_dynamic_title_padding_vertical)
            titleTextView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            updateTitleTextHeight(titleTextView, resources.getDimensionPixelSize(androidx.leanback.R.dimen.lb_browse_title_height))
            return
        }

        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(androidx.leanback.R.dimen.lb_browse_title_text_size))
        titleTextView.setSingleLine(true)
        titleTextView.maxLines = 1
        titleTextView.maxWidth = Int.MAX_VALUE
        titleTextView.ellipsize = TextUtils.TruncateAt.END
        titleTextView.marqueeRepeatLimit = 0
        titleTextView.gravity = Gravity.END
        titleTextView.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        titleTextView.includeFontPadding = true
        titleTextView.isSelected = false
        titleTextView.background = null
        titleTextView.setPadding(0, 0, 0, 0)
        updateTitleTextHeight(titleTextView, resources.getDimensionPixelSize(androidx.leanback.R.dimen.lb_browse_title_height))
    }

    private fun updateTitleTextHeight(titleTextView: TextView, height: Int) {
        titleTextView.layoutParams?.let { layoutParams ->
            if (layoutParams.height != height) {
                layoutParams.height = height
                titleTextView.layoutParams = layoutParams
            }
        }
    }

    private fun applyFoldersRowChange(visibleRows: List<PageRow>) {
        val currentFoldersIndex = mRowsAdapter.indexOfFirstRowNamed(FOLDERS_HEADER_NAME)
        val desiredFoldersIndex = visibleRows.indexOfFirst { it.headerItem.name == FOLDERS_HEADER_NAME }

        if (desiredFoldersIndex >= 0 && currentFoldersIndex == -1) {
            mRowsAdapter.add(desiredFoldersIndex, visibleRows[desiredFoldersIndex])
        } else if (desiredFoldersIndex == -1 && currentFoldersIndex >= 0) {
            mRowsAdapter.removeItems(currentFoldersIndex, 1)
        }
    }

    fun focusHeadersFromMainFragment() {
        startHeadersTransition(true)
        view?.post {
            requestHeadersFocus()
            view?.postDelayed({ requestHeadersFocus() }, HEADERS_FOCUS_RETRY_DELAY_MS)
        }
    }

    private fun requestHeadersFocus() {
        headersSupportFragment.setSelectedPosition(selectedPosition, false)
        headersSupportFragment.verticalGridView?.requestFocus()
    }

    private fun getSelectedHeaderName(): String? {
        if (!::mRowsAdapter.isInitialized || selectedPosition !in 0 until mRowsAdapter.size()) {
            return null
        }

        return (mRowsAdapter.get(selectedPosition) as? PageRow)?.headerItem?.name
    }

    private fun ArrayObjectAdapter.indexOfFirstRowNamed(name: String): Int {
        for (index in 0 until size()) {
            val row = get(index) as? PageRow
            if (row?.headerItem?.name == name) {
                return index
            }
        }

        return -1
    }

    private fun createRows(): List<PageRow> {
        showingFolders = PreferenceManager.get(SHOW_FOLDERS_IN_MAIN_COLUMN)
        return HEADERS.mapIndexedNotNull { index, header ->
            if (header.name == FOLDERS_HEADER_NAME && !showingFolders) {
                null
            } else {
                PageRow(HeaderItem(index.toLong(), header.name))
            }
        }
    }

    private class PageRowFragmentFactory : FragmentFactory<Fragment>() {
        override fun createFragment(rowObj: Any): Fragment {
            val row = rowObj as Row
            Timber.i("Going to show page: ${row.headerItem.name}")
            return HEADERS[row.headerItem.id.toInt()].fragment()
        }
    }

    override fun onDestroy() {
        PreferenceManager.sharedPreference.unregisterOnSharedPreferenceChangeListener(showFoldersPreferenceListener)
        super.onDestroy()
    }

    companion object {
        private const val FOLDERS_HEADER_NAME = "Folders"
        private const val VIDEOS_HEADER_NAME = "Videos"
        private const val VIDEOS_DISPLAY_TITLE = "Video"
        private const val INITIAL_TITLE_REFRESH_DELAY_MS = 250L
        private const val HEADERS_FOCUS_RETRY_DELAY_MS = 150L
        private const val HEADER_FOCUS_BRIDGE_RETRY_DELAY_MS = 50L

        private val HEADERS: List<Header> = listOf(
            Header(VIDEOS_HEADER_NAME) { VideosBrowseFragment() },
            Header("Timeline") { nl.giejay.android.tv.immich.assets.IntegratedTimelineFragment() },
            Header("People") { PeopleFragment() },
            Header("On This Day") { OnThisDayBrowseFragment() },
            Header("Random") { RandomAssetsFragment() },
            Header("Recent") { RecentAssetsFragment() },
            Header("Favorites") { FavoritesBrowseFragment() },
            Header("Albums") {
                AlbumFragment().apply {
                    arguments = bundleOf("selectionMode" to false)
                }
            },
            Header("Seasonal") { SimilarTimeAssetsFragment() },
            Header("All") { AllAssetFragment() },
            Header(FOLDERS_HEADER_NAME) { FolderFragment() },
            Header("Settings") { SettingsFragment() },
        )
    }
}

class Header(val name: String, var show: Boolean = false, val fragment: () -> Fragment)
