package nl.giejay.android.tv.immich.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Bucket
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SHOW_ONLY_VIDEOS
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime

private data class TimelineBucketCache(
    val assets: MutableList<Asset> = mutableListOf(),
    var nextPage: Int = 1,
    var allPagesLoaded: Boolean = false
)

class TimelineViewModel : ViewModel() {

    companion object {
        private const val TIMELINE_PAGE_SIZE = 48
        private const val MAX_BUCKET_CACHE_SIZE = 6
    }

    private val _buckets = MutableStateFlow<List<Bucket>>(emptyList())
    val buckets: StateFlow<List<Bucket>> = _buckets

    private val _selectedBucketId = MutableStateFlow<String?>(null)
    val selectedBucketId: StateFlow<String?> = _selectedBucketId

    private var rawAssets: List<Asset> = emptyList()

    private val _assets = MutableStateFlow<List<Asset>>(emptyList())
    val assets: StateFlow<List<Asset>> = _assets

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isLoadingAssets = MutableStateFlow(false)
    val isLoadingAssets: StateFlow<Boolean> = _isLoadingAssets

    private val bucketCache = object : LinkedHashMap<String, TimelineBucketCache>(MAX_BUCKET_CACHE_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TimelineBucketCache>?): Boolean {
            return size > MAX_BUCKET_CACHE_SIZE
        }
    }

    private var bucketsLoaded = false
    private var bucketLoadJob: Job? = null
    private var activeBucketLoadId = 0

    fun loadBuckets(apiClient: ApiClient, forceReload: Boolean = false) {
        if (bucketsLoaded && _buckets.value.isNotEmpty() && !forceReload) {
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    apiClient.listBuckets("", PhotosOrder.NEWEST_OLDEST)
                }

                result.fold(
                    { error ->
                        Timber.e("Error loading buckets: $error")
                    },
                    { bucketList ->
                        _buckets.value = bucketList
                        bucketsLoaded = true

                        if (_selectedBucketId.value == null && bucketList.isNotEmpty()) {
                            selectBucket(bucketList.first().timeBucket, apiClient)
                        } else {
                            _selectedBucketId.value?.let { selectedBucketId ->
                                val cache = bucketCache[selectedBucketId]
                                if (cache != null && cache.assets.isNotEmpty()) {
                                    rawAssets = cache.assets.toList()
                                    applyFilterAndEmit()
                                } else {
                                    loadFirstPage(selectedBucketId, apiClient)
                                }
                            }
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Exception loading buckets")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectBucket(bucketId: String, apiClient: ApiClient) {
        val alreadySelected = _selectedBucketId.value == bucketId
        _selectedBucketId.value = bucketId

        val cache = bucketCache[bucketId]
        if (cache != null && cache.assets.isNotEmpty()) {
            rawAssets = cache.assets.toList()
            applyFilterAndEmit()
            if (alreadySelected) {
                return
            }
        } else {
            rawAssets = emptyList()
            _assets.value = emptyList()
            loadFirstPage(bucketId, apiClient)
            return
        }
    }

    fun maybeLoadMoreForSelectedBucket(apiClient: ApiClient) {
        val bucketId = _selectedBucketId.value ?: return
        val cache = bucketCache[bucketId] ?: return
        if (cache.allPagesLoaded || _isLoadingAssets.value) {
            return
        }
        launchBucketLoad(bucketId, apiClient, reset = false, cancelExisting = false)
    }

    suspend fun loadMoreForSelectedBucket(apiClient: ApiClient): List<Asset> {
        val bucketId = _selectedBucketId.value ?: return emptyList()
        val cache = bucketCache[bucketId] ?: return emptyList()
        if (cache.allPagesLoaded || _isLoadingAssets.value) {
            return emptyList()
        }

        val loadId = ++activeBucketLoadId
        _isLoadingAssets.value = true
        return try {
            loadBucketPageInternal(bucketId, apiClient, reset = false)
        } finally {
            if (loadId == activeBucketLoadId) {
                _isLoadingAssets.value = false
            }
        }
    }

    fun refreshFilter() {
        applyFilterAndEmit()
    }

    fun forceReload(apiClient: ApiClient) {
        bucketLoadJob?.cancel()
        bucketsLoaded = false
        rawAssets = emptyList()
        _buckets.value = emptyList()
        _assets.value = emptyList()
        bucketCache.clear()
        loadBuckets(apiClient, forceReload = true)
    }

    fun getSelectedBucket(): Bucket? {
        val selectedId = _selectedBucketId.value ?: return null
        return _buckets.value.find { it.timeBucket == selectedId }
    }

    private fun loadFirstPage(bucketId: String, apiClient: ApiClient) {
        launchBucketLoad(bucketId, apiClient, reset = true, cancelExisting = true)
    }

    private fun launchBucketLoad(bucketId: String, apiClient: ApiClient, reset: Boolean, cancelExisting: Boolean) {
        if (cancelExisting) {
            bucketLoadJob?.cancel()
        } else if (bucketLoadJob?.isActive == true) {
            return
        }

        val loadId = ++activeBucketLoadId
        bucketLoadJob = viewModelScope.launch {
            _isLoadingAssets.value = true
            try {
                loadBucketPageInternal(bucketId, apiClient, reset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Exception loading timeline assets for $bucketId")
            } finally {
                if (loadId == activeBucketLoadId) {
                    _isLoadingAssets.value = false
                }
            }
        }
    }

    private suspend fun loadBucketPageInternal(bucketId: String, apiClient: ApiClient, reset: Boolean): List<Asset> {
        val cache = bucketCache.getOrPut(bucketId) { TimelineBucketCache() }

        if (reset) {
            cache.assets.clear()
            cache.nextPage = 1
            cache.allPagesLoaded = false
        }

        if (cache.allPagesLoaded) {
            if (_selectedBucketId.value == bucketId) {
                rawAssets = cache.assets.toList()
                applyFilterAndEmit()
            }
            return emptyList()
        }

        val (fromDate, endDate) = monthRange(bucketId)
        val pageToLoad = cache.nextPage
        val response = withContext(Dispatchers.IO) {
            apiClient.listAssets(
                page = pageToLoad,
                pageCount = TIMELINE_PAGE_SIZE,
                random = false,
                order = "desc",
                fromDate = fromDate,
                endDate = endDate,
                contentType = timelineContentType()
            )
        }

        return response.fold(
            { error ->
                Timber.e("Error loading timeline assets for $bucketId: $error")
                emptyList()
            },
            { assetsPage ->
                val existingIds = cache.assets.asSequence().map { it.id }.toHashSet()
                val newAssets = assetsPage.filterNot { existingIds.contains(it.id) }

                cache.assets.addAll(newAssets)
                cache.nextPage = pageToLoad + 1
                cache.allPagesLoaded = assetsPage.size < TIMELINE_PAGE_SIZE

                if (_selectedBucketId.value == bucketId) {
                    rawAssets = cache.assets.toList()
                    applyFilterAndEmit()
                }

                newAssets
            }
        )
    }

    private fun applyFilterAndEmit() {
        val showOnlyVideos = PreferenceManager.get(SHOW_ONLY_VIDEOS)
        _assets.value = if (showOnlyVideos) {
            rawAssets.filter { it.type.equals("VIDEO", ignoreCase = true) }
        } else {
            rawAssets
        }
    }

    private fun timelineContentType(): ContentType {
        return if (PreferenceManager.get(SHOW_ONLY_VIDEOS)) {
            ContentType.VIDEO
        } else {
            ContentType.ALL
        }
    }

    private fun monthRange(bucketId: String): Pair<LocalDateTime, LocalDateTime> {
        val monthStart = LocalDate.parse(bucketId).atStartOfDay()
        val monthEnd = monthStart.plusMonths(1).minusSeconds(1)
        return monthStart to monthEnd
    }
}
