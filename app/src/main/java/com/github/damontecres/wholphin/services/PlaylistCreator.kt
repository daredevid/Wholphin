package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.Playlist
import com.github.damontecres.wholphin.data.model.PlaylistInfo
import com.github.damontecres.wholphin.data.model.PlaylistItem
import com.github.damontecres.wholphin.ui.DetailItemFields
import com.github.damontecres.wholphin.ui.components.baseItemKinds
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.gt
import com.github.damontecres.wholphin.ui.indexOfFirstOrNull
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.GetEpisodesRequestHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaylistUserPermissions
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trim an expanded playlist so the queue starts at [startItemId]
 *
 * Falls back to the full list when [startItemId] is null or cannot be found (for example when the item
 * was filtered out).
 */
fun trimFromItem(
    items: List<BaseItemDto>,
    startItemId: UUID?,
): List<BaseItemDto> {
    if (startItemId == null) return items
    val index = items.indexOfFirst { it.id == startItemId }
    return if (index >= 0) items.subList(index, items.size) else items
}

/**
 * Create [Playlist]s (not Jellyfin server playlist) for playback
 *
 * Used to create a queue of episodes or from Play All button
 */
@Singleton
class PlaylistCreator
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
    ) {
        /**
         * Creates a playlist of next up episodes for the given series starting with the given episode
         */
        private suspend fun createFromEpisode(
            seriesId: UUID,
            episodeId: UUID?,
            seasonId: UUID? = null,
            shuffled: Boolean = false,
        ): Playlist {
            val request =
                GetEpisodesRequest(
                    seriesId = seriesId,
                    seasonId = seasonId,
                    fields = DetailItemFields,
                    startItemId = episodeId,
                    sortBy = if (shuffled) ItemSortBy.RANDOM else null,
                    limit = Playlist.MAX_SIZE,
                )
            val episodes =
                GetEpisodesRequestHandler
                    .execute(api, request)
                    .content.items
                    .convertAndAddParts(false)
            val startIndex =
                episodeId?.let { episodes.indexOfFirstOrNull { it.id == episodeId } } ?: 0
            return Playlist(episodes.subList(startIndex, episodes.size))
        }

        /**
         * Create from a server playlist ID
         *
         * Collections ([BaseItemKind.BOX_SET]) in the playlist are expanded into their children so the
         * queue matches the expanded list shown by the playlist details screen. The returned window starts at
         * the entry [startIndex] and, when [startItemId] is given, is trimmed so that item is the first entry
         * of the flattened queue.
         */
        private suspend fun createFromPlaylistId(
            playlistId: UUID,
            startIndex: Int?,
            sortAndDirection: SortAndDirection,
            filter: GetItemsFilter,
            startItemId: UUID? = null,
        ): Playlist {
            val request =
                filter.applyTo(
                    GetItemsRequest(
                        userId = serverRepository.currentUser?.id,
                        parentId = playlistId,
                        fields = DetailItemFields,
                        startIndex = startIndex,
                        limit = Playlist.MAX_SIZE,
                        sortBy = listOf(sortAndDirection.sort),
                        sortOrder = listOf(sortAndDirection.direction),
                        excludeLocationTypes = listOf(LocationType.VIRTUAL),
                    ),
                )
            val items =
                trimFromItem(
                    items =
                        expandBoxSets(
                            rawItems = GetItemsRequestHandler.execute(api, request).content.items,
                            filter = filter,
                            sortAndDirection = sortAndDirection,
                        ),
                    startItemId = startItemId,
                )
            return Playlist(items.convertAndAddParts())
        }

        /**
         * Replaces each [BaseItemKind.BOX_SET] in [rawItems] with its children, keeping every other entry as-is.
         *
         * If a collection cannot be read, it is kept as a single entry (skipped during playback, as before).
         */
        private suspend fun expandBoxSets(
            rawItems: List<BaseItemDto>,
            filter: GetItemsFilter,
            sortAndDirection: SortAndDirection,
        ): List<BaseItemDto> =
            if (rawItems.none { it.type == BaseItemKind.BOX_SET }) {
                rawItems
            } else {
                buildList {
                    rawItems.forEach { entry ->
                        if (entry.type == BaseItemKind.BOX_SET) {
                            val children =
                                try {
                                    getCollectionChildren(
                                        boxSet = entry,
                                        filter = filter,
                                        sortAndDirection = sortAndDirection,
                                        fields = DetailItemFields,
                                    )
                                } catch (ex: CancellationException) {
                                    throw ex
                                } catch (ex: Exception) {
                                    Timber.w(ex, "Could not expand collection %s for playback", entry.id)
                                    emptyList()
                                }
                            if (children.isNotEmpty()) {
                                addAll(children)
                            } else {
                                add(entry)
                            }
                        } else {
                            add(entry)
                        }
                    }
                }
            }

        /**
         * Build the request for the items contained in a collection, using the same rules as playback does
         *
         * @param userId when set, user data (watched/favorite) is included for that user
         */
        private fun collectionChildrenRequest(
            boxSet: BaseItemDto,
            filter: GetItemsFilter,
            sortAndDirection: SortAndDirection?,
            fields: List<ItemFields>,
            userId: UUID? = null,
            startIndex: Int? = null,
            limit: Int? = null,
        ): GetItemsRequest {
            val includeItemTypes =
                boxSet.collectionType
                    ?.baseItemKinds
                    ?.filter { it.playable }
                    ?.takeIf { it.isNotEmpty() }
                    ?: BaseItemKind.entries.filter { it.playable }
            return filter.applyTo(
                overwriteIncludeTypes = false,
                req =
                    GetItemsRequest(
                        userId = userId,
                        parentId = boxSet.id,
                        enableImageTypes =
                            listOf(
                                ImageType.PRIMARY,
                                ImageType.THUMB,
                                ImageType.LOGO,
                            ),
                        includeItemTypes = includeItemTypes,
                        recursive = true,
                        excludeItemIds = listOf(boxSet.id),
                        sortBy = sortAndDirection?.let { listOf(sortAndDirection.sort) },
                        sortOrder = sortAndDirection?.let { listOf(sortAndDirection.direction) },
                        fields = fields,
                        startIndex = startIndex,
                        limit = limit,
                        excludeLocationTypes = listOf(LocationType.VIRTUAL),
                    ),
            )
        }

        /**
         * Get the items inside a collection as plain [BaseItemDto]s (not converted into a playback queue)
         */
        suspend fun getCollectionChildren(
            boxSet: BaseItemDto,
            filter: GetItemsFilter,
            sortAndDirection: SortAndDirection?,
            fields: List<ItemFields>,
            userId: UUID? = null,
        ): List<BaseItemDto> {
            val request = collectionChildrenRequest(boxSet, filter, sortAndDirection, fields, userId)
            return GetItemsRequestHandler.execute(api, request).content.items
        }

        private suspend fun createFromCollection(
            item: BaseItemDto,
            startIndex: Int = 0,
            sortAndDirection: SortAndDirection?,
            filter: GetItemsFilter,
        ): Playlist {
            val request =
                collectionChildrenRequest(
                    boxSet = item,
                    filter = filter,
                    sortAndDirection = sortAndDirection,
                    fields = DetailItemFields,
                    startIndex = startIndex,
                    limit = Playlist.MAX_SIZE,
                )
            val items =
                GetItemsRequestHandler
                    .execute(api, request)
                    .content.items
                    .convertAndAddParts()
            return Playlist(items)
        }

        /**
         * Create a [Playlist] contextually based on the given item.
         *
         * For example, an episode creates a queue of next up episodes in the series, or a movie creates one for its parts if needed
         */
        suspend fun createFrom(
            item: BaseItemDto,
            startIndex: Int = 0,
            sortAndDirection: SortAndDirection? = SortAndDirection.DEFAULT,
            shuffled: Boolean = false,
            recursive: Boolean = false,
            filter: GetItemsFilter = GetItemsFilter(),
            startItemId: UUID? = null,
        ): PlaylistCreationResult =
            when (item.type) {
                BaseItemKind.BOX_SET,
                BaseItemKind.COLLECTION_FOLDER,
                BaseItemKind.USER_VIEW,
                -> {
                    PlaylistCreationResult.Success(
                        createFromCollection(
                            item = item,
                            startIndex = startIndex,
                            sortAndDirection =
                                if (shuffled) {
                                    SortAndDirection(ItemSortBy.RANDOM, SortOrder.ASCENDING)
                                } else {
                                    sortAndDirection
                                },
                            filter = filter,
                        ),
                    )
                }

                BaseItemKind.EPISODE -> {
                    val seriesId = item.seriesId
                    if (seriesId != null) {
                        PlaylistCreationResult.Success(
                            createFromEpisode(
                                seriesId = seriesId,
                                seasonId = null,
                                episodeId = item.id,
                                shuffled = shuffled,
                            ),
                        )
                    } else {
                        PlaylistCreationResult.Error(null, "Episode has no seriesId")
                    }
                }

                BaseItemKind.SEASON -> {
                    val seriesId = item.seriesId
                    if (seriesId != null) {
                        if (shuffled) {
                            api.tvShowsApi
                                .getEpisodes(
                                    seriesId = seriesId,
                                    seasonId = item.id,
                                    limit = Playlist.MAX_SIZE,
                                    sortBy = ItemSortBy.RANDOM,
                                    fields = DetailItemFields,
                                ).content.items
                                .convertAndAddParts()
                                .let {
                                    PlaylistCreationResult.Success(Playlist(it))
                                }
                        } else {
                            PlaylistCreationResult.Success(
                                createFromEpisode(
                                    seriesId = seriesId,
                                    seasonId = item.id,
                                    episodeId = null,
                                    shuffled = shuffled,
                                ),
                            )
                        }
                    } else {
                        PlaylistCreationResult.Error(null, "Episode has no seriesId")
                    }
                }

                BaseItemKind.SERIES -> {
                    if (shuffled) {
                        api.tvShowsApi
                            .getEpisodes(
                                seriesId = item.id,
                                limit = Playlist.MAX_SIZE,
                                sortBy = ItemSortBy.RANDOM,
                                fields = DetailItemFields,
                            ).content.items
                            .convertAndAddParts()
                            .let {
                                PlaylistCreationResult.Success(Playlist(it))
                            }
                    } else {
                        val result by api.tvShowsApi.getNextUp(seriesId = item.id)
                        val nextUp =
                            result.items.firstOrNull() ?: api.tvShowsApi
                                .getEpisodes(
                                    item.id,
                                    limit = 1,
                                ).content.items
                                .firstOrNull()
                        if (nextUp != null) {
                            PlaylistCreationResult.Success(
                                createFromEpisode(
                                    seriesId = item.id,
                                    seasonId = null,
                                    episodeId = nextUp.id,
                                    shuffled = shuffled,
                                ),
                            )
                        } else {
                            PlaylistCreationResult.Error(
                                null,
                                "Could not determine next up episode for series: " + item.id,
                            )
                        }
                    }
                }

                BaseItemKind.PLAYLIST -> {
                    PlaylistCreationResult.Success(
                        createFromPlaylistId(
                            playlistId = item.id,
                            startIndex = startIndex,
                            sortAndDirection =
                                if (shuffled) {
                                    SortAndDirection(ItemSortBy.RANDOM, SortOrder.ASCENDING)
                                } else {
                                    sortAndDirection ?: SortAndDirection(
                                        ItemSortBy.DEFAULT,
                                        SortOrder.ASCENDING,
                                    )
                                },
                            filter = filter,
                            startItemId = startItemId,
                        ),
                    )
                }

                BaseItemKind.MOVIE,
                BaseItemKind.VIDEO,
                BaseItemKind.MUSIC_VIDEO,
                BaseItemKind.TRAILER,
                -> {
                    val list =
                        buildList {
                            add(PlaylistItem.Media(BaseItem(item, false)))

                            if (item.partCount.gt(1)) {
                                api.videosApi
                                    .getAdditionalPart(item.id)
                                    .content.items
                                    .map {
                                        PlaylistItem.Media(BaseItem(it, false))
                                    }.let(::addAll)
                            }
                        }
                    PlaylistCreationResult.Success(Playlist(list))
                }

                // Not support yet
//                BaseItemKind.AGGREGATE_FOLDER -> TODO()
//                BaseItemKind.FOLDER -> TODO()
//                BaseItemKind.GENRE -> TODO()
//                BaseItemKind.MANUAL_PLAYLISTS_FOLDER -> TODO()
//                BaseItemKind.MUSIC_ALBUM -> TODO()
//                BaseItemKind.MUSIC_ARTIST -> TODO()

                else -> {
                    PlaylistCreationResult.Error(null, "Unsupported type: ${item.type}")
                }
            }

        private suspend fun List<BaseItemDto>.convertAndAddParts(useSeriesForPrimary: Boolean = false): List<PlaylistItem> =
            buildList {
                this@convertAndAddParts
                    .filter { it.locationType != LocationType.VIRTUAL }
                    .forEach { ep ->
                        add(PlaylistItem.Media(BaseItem(ep, useSeriesForPrimary)))
                        if (ep.partCount.gt(1)) {
                            val parts =
                                api.videosApi.getAdditionalPart(ep.id).content.items.map { part ->
                                    PlaylistItem.Media(BaseItem(part, useSeriesForPrimary))
                                }
                            addAll(parts)
                        }
                    }
            }

        /**
         * Get the playlists on the server for a given media type
         */
        suspend fun getServerPlaylists(
            query: String,
            mediaType: MediaType?,
        ): List<PlaylistInfo> {
            val userId =
                serverRepository.currentUser?.id ?: throw IllegalStateException("No user found")
            val request =
                GetItemsRequest(
                    searchTerm = query.takeIf { it.isNotBlank() },
                    includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                    mediaTypes = mediaType?.let { listOf(mediaType) },
                    recursive = true,
                    limit = 25,
                    sortBy = listOf(ItemSortBy.DATE_LAST_CONTENT_ADDED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                )
            val playlists = GetItemsRequestHandler.execute(api, request).content.items
            return playlists
                .mapNotNull { playlist ->
                    try {
                        val response = api.playlistsApi.getPlaylistUser(playlist.id, userId).content
                        if (response.canEdit) {
                            PlaylistInfo(
                                id = playlist.id,
                                name = playlist.name ?: context.getString(R.string.unknown),
                                count = playlist.childCount ?: 0,
                                mediaType = playlist.mediaType,
                            )
                        } else {
                            null
                        }
                    } catch (ex: InvalidStatusException) {
                        if (ex.status == 404) {
                            null
                        } else {
                            throw ex
                        }
                    }
                }
//                .sortedWith(
//                    compareBy<PlaylistInfo> {
//                        SearchRelevance.score(it.name, BaseItemKind.PLAYLIST, query)
//                    }.thenBy { it.name },
//                )
        }

        suspend fun createServerPlaylist(
            name: String,
            initialItems: List<UUID>,
        ): UUID? =
            serverRepository.currentUser?.let { user ->
                api.playlistsApi
                    .createPlaylist(
                        CreatePlaylistDto(
                            name = name,
                            ids = initialItems,
                            users = listOf(PlaylistUserPermissions(user.id, true)),
                            isPublic = false,
                        ),
                    ).content.id
                    .toUUIDOrNull()
            }

        suspend fun addToServerPlaylist(
            playlistId: UUID,
            itemId: UUID,
        ) {
            api.playlistsApi.addItemToPlaylist(playlistId, listOf(itemId))
        }

        suspend fun removeFromServerPlaylist(
            playlistId: UUID,
            itemId: UUID,
        ) {
            api.playlistsApi.removeItemFromPlaylist(
                playlistId.toServerString(),
                listOf(itemId.toServerString()),
            )
        }
    }

sealed interface PlaylistCreationResult {
    data class Success(
        val playlist: Playlist,
    ) : PlaylistCreationResult

    data class Error(
        val ex: Exception?,
        val message: String?,
    ) : PlaylistCreationResult
}
