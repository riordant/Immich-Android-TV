package nl.giejay.android.tv.immich.api.util

import arrow.core.Either
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import retrofit2.HttpException
import retrofit2.Response
import timber.log.Timber
import java.util.UUID

object ApiUtil {

    fun normalizeHostName(hostName: String): String {
        val trimmedHost = hostName.trim().trimEnd('/')
        return if (trimmedHost.endsWith("/api", ignoreCase = true)) {
            trimmedHost.dropLast(4).trimEnd('/')
        } else {
            trimmedHost
        }
    }

    fun getThumbnailUrl(assetId: String?, format: String): String? {
        return assetId?.let {
            "${hostName().lowercase()}/api/assets/${it}/thumbnail?size=${format}"
        }
    }

    private fun hostName(): String {
        return normalizeHostName(PreferenceManager.get(HOST_NAME))
    }

    fun getFileUrl(assetId: String?, type: String): String? {
        return when (type) {
            "VIDEO" ->
                assetId?.let {
                    "${hostName().lowercase()}/api/assets/${it}/video/playback"
                }

            else ->
                assetId?.let {
                    "${hostName().lowercase()}/api/assets/${it}/original"
                }

        }
    }

    fun getPersonThumbnail(personId: UUID): String {
        return "${hostName().lowercase()}/api/people/$personId/thumbnail"
    }

    suspend fun <T> executeAPICall(expectedStatus: Int, handler: suspend () -> Response<T>): Either<String, T> {
        try {
            Timber.d("APIUTIL: executeAPICall called")
            val res = handler()
            Timber.d("APIUTIL: Response code: ${res.code()}, expected: $expectedStatus")
            return when (val code = res.code()) {
                expectedStatus -> {
                    val body = res.body()
                    if (body != null) return Either.Right(body)

                    return try {
                        Either.Right(Unit as T)
                    } catch (e: Exception) {
                        Either.Left("Did not receive a input from the server")
                    }
                }

                403 -> {
                    if(res.errorBody()?.string()?.contains("required permission: all") == true){
                        Either.Left("API key is missing the permission \"all\". Please adapt your permissions in the Immich web interface.")
                    } else {
                        Either.Left("API key permissions are invalid: ${res.errorBody()?.string() ?: "Unknown error"}")
                    }
                }
                else -> {
                    Timber.e("APIUTIL: Invalid status code: $code, expected: $expectedStatus")
                    Either.Left("Invalid status code from API: $code, make sure you are using the latest Immich server release.")
                }
            }
        } catch (e: HttpException) {
            Timber.e(e, "Could not fetch items due to http error")
            return Either.Left("Could not fetch data from API, status: ${e.code()}")
        } catch (e: Exception) {
            Timber.e(e, "Could not fetch items, unknown error")
            return Either.Left("Could not fetch data from API, response: ${e.message}")
        }
    }
}
