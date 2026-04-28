package nl.giejay.android.tv.immich.api.service

import nl.giejay.android.tv.immich.api.model.Album
import nl.giejay.android.tv.immich.api.model.AlbumDetails
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Bucket
// Importante: importamos nuestra nueva clase de respuesta
import nl.giejay.android.tv.immich.api.model.BucketResponse 
import nl.giejay.android.tv.immich.api.model.PeopleResponse
import nl.giejay.android.tv.immich.api.model.SearchRequest
import nl.giejay.android.tv.immich.api.model.SearchResponse
import nl.giejay.android.tv.immich.api.model.DeleteAssetsRequest
import nl.giejay.android.tv.immich.api.model.RecentVideoUpdateRequest
import nl.giejay.android.tv.immich.api.model.UpdateAssetRequest
import nl.giejay.android.tv.immich.api.model.VideoPlaybackEntry
import nl.giejay.android.tv.immich.api.model.VideoPlaybackResponse
import nl.giejay.android.tv.immich.api.model.VideoPlaybackUpdateRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("search/metadata")
    suspend fun listAssets(@Body searchRequest: SearchRequest): Response<SearchResponse>

    @POST("search/random")
    suspend fun randomAssets(@Body searchRequest: SearchRequest): Response<List<Asset>>

    @GET("albums")
    suspend fun listAlbums(@Query("shared") shared: Boolean = false): Response<List<Album>>

    @GET("people")
    suspend fun listPeople(): Response<PeopleResponse>

    @GET("albums/{albumId}")
    suspend fun listAssetsFromAlbum(@Path("albumId") albumId: String?): Response<AlbumDetails>

    @GET("timeline/buckets")
    suspend fun listBuckets(@Query("albumId") albumId: String?, @Query("size") size: String = "MONTH", @Query("order") order: String = "desc"): Response<List<Bucket>>

    // AQUÍ ESTÁ EL CAMBIO IMPORTANTE: Devuelve BucketResponse
    @GET("timeline/bucket")
    suspend fun getBucket(
        @Query("albumId") albumId: String?, 
        @Query("timeBucket") timeBucket: String, 
        @Query("size") size: String = "MONTH", 
        @Query("order") order: String = "desc"
    ): Response<BucketResponse>

    @GET("timeline/bucket")
    suspend fun getBucketV2(@Query("albumId") albumId: String?, @Query("timeBucket") timeBucket: String, @Query("size") size: String = "MONTH"): Response<BucketResponse>

    @GET("assets/{id}")
    suspend fun getAsset(@Path("id") id: String): Response<Asset>

    @GET("users/me/recent-videos")
    suspend fun getRecentVideos(): Response<List<Asset>>

    @PUT("users/me/recent-videos")
    suspend fun updateRecentVideos(@Body body: RecentVideoUpdateRequest): Response<List<Asset>>

    @GET("users/me/video-playback")
    suspend fun getVideoPlaybacks(): Response<List<VideoPlaybackEntry>>

    @GET("users/me/video-playback/{id}")
    suspend fun getVideoPlayback(@Path("id") id: String): Response<VideoPlaybackResponse>

    @PUT("users/me/video-playback")
    suspend fun updateVideoPlayback(@Body body: VideoPlaybackUpdateRequest): Response<VideoPlaybackResponse>

    @GET("view/folder/unique-paths")
    suspend fun getUniquePaths(): Response<List<String>>

    @GET("view/folder")
    suspend fun getAssetsForPath(@Query("path") path: String): Response<List<Asset>>

    @retrofit2.http.PUT("assets/{id}")
        suspend fun updateAsset(
            @Path("id") id: String,
            @Body body: UpdateAssetRequest
        ): Response<Asset>

    @retrofit2.http.HTTP(method = "DELETE", path = "assets", hasBody = true)
    suspend fun deleteAssets(@Body body: DeleteAssetsRequest): Response<Unit>
}
