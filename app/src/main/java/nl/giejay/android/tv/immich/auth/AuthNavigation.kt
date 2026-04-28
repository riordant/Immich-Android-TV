package nl.giejay.android.tv.immich.auth

import androidx.navigation.NavController
import androidx.navigation.NavDirections
import androidx.navigation.NavOptions
import nl.giejay.android.tv.immich.R

fun NavController.navigateToFreshHome(directions: NavDirections) {
    navigate(
        directions,
        NavOptions.Builder()
            .setPopUpTo(R.id.authFragment, true)
            .setLaunchSingleTop(true)
            .build()
    )
}
