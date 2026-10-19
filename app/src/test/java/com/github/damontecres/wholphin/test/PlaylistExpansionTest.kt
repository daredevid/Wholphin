package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.services.trimFromItem
import com.github.damontecres.wholphin.ui.detail.buildExpandedPlaylistRows
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistExpansionTest {
    @Test
    fun playlistWithoutCollections_keepsRawEntries() {
        val first = movie(id = UUID.randomUUID(), name = "A")
        val second = movie(id = UUID.randomUUID(), name = "B")

        val rows = buildExpandedPlaylistRows(listOf(first, second), emptyMap())

        assertEquals(listOf(first.id, second.id), rows.items.map { it.id })
        assertEquals(2, rows.sources.size)
        assertEquals(0, rows.sources[0].sourceIndex)
        assertEquals(first.id, rows.sources[0].sourceItemId)
        assertFalse(rows.sources[0].expanded)
        assertEquals(1, rows.sources[1].sourceIndex)
        assertEquals(second.id, rows.sources[1].sourceItemId)
        assertFalse(rows.sources[1].expanded)
    }

    @Test
    fun collectionWithChildren_isExpandedInPlace() {
        val before = movie(id = UUID.randomUUID(), name = "Before")
        val collection = item(BaseItemKind.BOX_SET, id = UUID.randomUUID(), name = "Collection")
        val childA = movie(id = UUID.randomUUID(), name = "Child A")
        val childB = movie(id = UUID.randomUUID(), name = "Child B")
        val after = movie(id = UUID.randomUUID(), name = "After")

        val rows =
            buildExpandedPlaylistRows(
                listOf(before, collection, after),
                mapOf(collection.id to listOf(childA, childB)),
            )

        assertEquals(
            listOf(before.id, childA.id, childB.id, after.id),
            rows.items.map { it.id },
        )
        assertEquals(
            listOf(false, true, true, false),
            rows.sources.map { it.expanded },
        )
        assertEquals(
            listOf(0, 1, 1, 2),
            rows.sources.map { it.sourceIndex },
        )
        rows.sources.forEachIndexed { index, source ->
            val expectedEntry =
                when (index) {
                    0 -> before.id
                    1, 2 -> collection.id
                    else -> after.id
                }
            assertEquals(expectedEntry, source.sourceItemId)
        }
    }

    @Test
    fun collectionWithoutChildren_staysSingleCollectionRow() {
        val collection = item(BaseItemKind.BOX_SET, id = UUID.randomUUID(), name = "Empty")
        val after = movie(id = UUID.randomUUID(), name = "After")

        val rows =
            buildExpandedPlaylistRows(
                listOf(collection, after),
                mapOf(collection.id to emptyList()),
            )

        assertEquals(listOf(collection.id, after.id), rows.items.map { it.id })
        assertEquals(listOf(false, false), rows.sources.map { it.expanded })
        assertEquals(listOf(0, 1), rows.sources.map { it.sourceIndex })
    }

    @Test
    fun collectionMissingFromChildrenMap_staysSingleCollectionRow() {
        val collection = item(BaseItemKind.BOX_SET, id = UUID.randomUUID(), name = "Unknown")

        val rows = buildExpandedPlaylistRows(listOf(collection), emptyMap())

        assertEquals(listOf(collection.id), rows.items.map { it.id })
        assertFalse(rows.sources.single().expanded)
        assertEquals(0, rows.sources.single().sourceIndex)
        assertTrue(rows.sources.single().sourceItemId == collection.id)
    }

    @Test
    fun trimFromItem_secondChild_startsAtThatChild() {
        val first = movie(id = UUID.randomUUID(), name = "A")
        val second = movie(id = UUID.randomUUID(), name = "B")
        val third = movie(id = UUID.randomUUID(), name = "C")

        val trimmed = trimFromItem(listOf(first, second, third), second.id)

        assertEquals(listOf(second.id, third.id), trimmed.map { it.id })
    }

    @Test
    fun trimFromItem_nullStartItemId_keepsFullList() {
        val first = movie(id = UUID.randomUUID(), name = "A")
        val second = movie(id = UUID.randomUUID(), name = "B")

        val trimmed = trimFromItem(listOf(first, second), null)

        assertEquals(listOf(first.id, second.id), trimmed.map { it.id })
    }

    @Test
    fun trimFromItem_unknownItemId_fallsBackToFullList() {
        val first = movie(id = UUID.randomUUID(), name = "A")
        val second = movie(id = UUID.randomUUID(), name = "B")

        val trimmed = trimFromItem(listOf(first, second), UUID.randomUUID())

        assertEquals(listOf(first.id, second.id), trimmed.map { it.id })
    }
}
