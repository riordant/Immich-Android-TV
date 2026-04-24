package nl.giejay.android.tv.immich.home

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
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
import timber.log.Timber

class HomeFragment : BrowseSupportFragment() {
    private lateinit var mRowsAdapter: ArrayObjectAdapter
    private lateinit var rows: List<PageRow>
    private var showingFolders = false
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
        headersSupportFragment.setOnHeaderViewSelectedListener { _, row ->
            title = row?.headerItem?.name ?: "-"
            selectedPosition = row?.let { mRowsAdapter.indexOf(it) } ?: 0
        }

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
            title = visibleRows[selectedIndex].headerItem.name
        } else if (!preserveSelectedHeader && visibleRows.isNotEmpty()) {
            title = visibleRows[selectedPosition.coerceIn(0, visibleRows.size - 1)].headerItem.name
        }
    }

    private fun refreshTitleFromSelectedRow() {
        getSelectedHeaderName()?.let { selectedHeaderName ->
            title = selectedHeaderName
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
        headersSupportFragment.verticalGridView.requestFocus()
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
        private const val INITIAL_TITLE_REFRESH_DELAY_MS = 250L
        private const val HEADERS_FOCUS_RETRY_DELAY_MS = 150L

        private val HEADERS: List<Header> = listOf(
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
