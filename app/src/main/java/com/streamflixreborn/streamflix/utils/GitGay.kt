package com.streamflixreborn.streamflix.utils

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

object GitGay {

    private val service = ApiService.build()


    object Releases {

        suspend fun getReleases(
            owner: String,
            repo: String,
        ): List<Release> {
            return service.getReleases(
                owner = owner,
                repo = repo,
            )
        }

        suspend fun getLatestRelease(
            owner: String,
            repo: String,
        ): Release {
            return service.getLatestRelease(
                owner = owner,
                repo = repo,
            )
        }
    }


    private interface ApiService {

        companion object {
            fun build(): ApiService {
                val retrofit = Retrofit.Builder()
                    .client(NetworkClient.noCookies)
                    .baseUrl("https://git.gay/api/v1/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                return retrofit.create(ApiService::class.java)
            }
        }

        @GET("repos/{owner}/{repo}/releases")
        suspend fun getReleases(
            @Path("owner") owner: String,
            @Path("repo") repo: String,
        ): List<Release>

        @GET("repos/{owner}/{repo}/releases/latest")
        suspend fun getLatestRelease(
            @Path("owner") owner: String,
            @Path("repo") repo: String,
        ): Release
    }


    data class Release(
        @SerializedName("url") val url: String,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("upload_url") val uploadUrl: String,
        @SerializedName("tarball_url") val tarballUrl: String?,
        @SerializedName("zipball_url") val zipballUrl: String?,
        @SerializedName("hide_archive_links") val hideArchiveLinks: Boolean = false,
        @SerializedName("id") val id: Int,
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("target_commitish") val targetCommitish: String,
        @SerializedName("name") val name: String?,
        @SerializedName("body") val body: String? = null,
        @SerializedName("draft") val draft: Boolean,
        @SerializedName("prerelease") val prerelease: Boolean,
        @SerializedName("created_at") val createdAt: String,
        @SerializedName("published_at") val publishedAt: String?,
        @SerializedName("author") val author: User,
        @SerializedName("assets") val assets: List<Asset>,
        @SerializedName("archive_download_count") val archiveDownloadCount: ArchiveDownloadCount? = null,
    ) {

        data class ArchiveDownloadCount(
            @SerializedName("zip") val zip: Int,
            @SerializedName("tar_gz") val tarGz: Int,
        )

        data class Asset(
            @SerializedName("browser_download_url") val browserDownloadUrl: String,
            @SerializedName("id") val id: Int,
            @SerializedName("name") val name: String,
            @SerializedName("size") val size: Long,
            @SerializedName("download_count") val downloadCount: Int,
            @SerializedName("created_at") val createdAt: String,
            @SerializedName("uuid") val uuid: String,
            @SerializedName("type") val type: String,
        )
    }

    data class User(
        @SerializedName("email") val email: String? = null,
        @SerializedName("login") val login: String,
        @SerializedName("id") val id: Int,
        @SerializedName("avatar_url") val avatarUrl: String,
        @SerializedName("html_url") val htmlUrl: String? = null,
        @SerializedName("login_name") val loginName: String? = null,
        @SerializedName("full_name") val fullName: String? = null,
        @SerializedName("username") val username: String? = null,
        @SerializedName("source_id") val sourceId: Int? = null,
        @SerializedName("language") val language: String? = null,
        @SerializedName("is_admin") val isAdmin: Boolean? = null,
        @SerializedName("last_login") val lastLogin: String? = null,
        @SerializedName("created") val created: String? = null,
        @SerializedName("restricted") val restricted: Boolean? = null,
        @SerializedName("active") val active: Boolean? = null,
        @SerializedName("prohibit_login") val prohibitLogin: Boolean? = null,
        @SerializedName("location") val location: String? = null,
        @SerializedName("pronouns") val pronouns: String? = null,
        @SerializedName("website") val website: String? = null,
        @SerializedName("description") val description: String? = null,
        @SerializedName("visibility") val visibility: String? = null,
        @SerializedName("followers_count") val followersCount: Int? = null,
        @SerializedName("following_count") val followingCount: Int? = null,
        @SerializedName("starred_repos_count") val starredReposCount: Int? = null,
    )
}
