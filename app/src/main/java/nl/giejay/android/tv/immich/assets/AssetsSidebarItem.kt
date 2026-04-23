package nl.giejay.android.tv.immich.assets

import nl.giejay.android.tv.immich.api.model.Bucket

sealed class AssetsSidebarItem {
    data class YearItem(val year: String) : AssetsSidebarItem()
    data class MonthItem(val bucket: Bucket, val isSelected: Boolean = false) : AssetsSidebarItem()
}
