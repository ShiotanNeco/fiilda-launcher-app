package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerSearchPersistenceTest {
    @Test
    fun documentTargetsRoundTripWithoutUsingHomePreferenceCodec() {
        val targets = listOf(
            SearchDocumentTarget(
                id = "tree:content://provider/tree/root",
                label = "ダウンロード",
                uri = "content://provider/tree/root",
                isTree = true,
            ),
            SearchDocumentTarget(
                id = "file:content://provider/document/a,b;c",
                label = "a,b;c.txt",
                uri = "content://provider/document/a,b;c",
                isTree = false,
            ),
        )
        val restored = parseSearchDocumentTargets(serializeSearchDocumentTargets(targets))
        assertEquals(targets, restored)
    }

    @Test
    fun newlyAcquiredGrantRollbackReleasesOnlyWhenNoOwnerRemains() {
        val before = PersistedReadGrantSnapshot(setOf("content://provider/document/existing"))
        assertTrue(
            shouldReleaseNewlyAcquiredReadGrant(
                uriString = "content://provider/document/new",
                before = before,
                afterUris = before.uris + "content://provider/document/new",
                currentOwners = emptyList(),
            ),
        )
        assertFalse(
            shouldReleaseNewlyAcquiredReadGrant(
                uriString = "content://provider/document/new",
                before = before,
                afterUris = before.uris + "content://provider/document/new",
                currentOwners = listOf("content://provider/document/new"),
            ),
        )
        assertFalse(
            shouldReleaseNewlyAcquiredReadGrant(
                uriString = "content://provider/document/existing",
                before = before,
                afterUris = before.uris,
                currentOwners = emptyList(),
            ),
        )
    }

    @Test
    fun rollbackReleasesRedundantChildOrTreeGrantWhenSnapshotAlreadyCoversOwner() {
        val tree = "content://provider/tree/root"
        val child = "content://provider/tree/root/document/root%2Fchild.txt"
        assertTrue(
            shouldReleaseNewlyAcquiredReadGrant(
                uriString = child,
                before = PersistedReadGrantSnapshot(setOf(tree)),
                afterUris = setOf(tree, child),
                currentOwners = listOf(tree),
            ),
        )
        assertTrue(
            shouldReleaseNewlyAcquiredReadGrant(
                uriString = tree,
                before = PersistedReadGrantSnapshot(setOf(child)),
                afterUris = setOf(child, tree),
                currentOwners = listOf(child),
            ),
        )
        // A new tree grant can be the only grant covering an existing child target, so preserve it
        // when the snapshot had no grant for that owner.
        assertFalse(
            shouldReleaseNewlyAcquiredReadGrant(
                uriString = tree,
                before = PersistedReadGrantSnapshot(emptySet()),
                afterUris = setOf(tree),
                currentOwners = listOf(child),
            ),
        )
    }

    @Test
    fun sharedUriReleasesOnlyAfterLastOwner() {
        val shared = "content://provider/document/shared"
        val other = "content://provider/document/other"
        val owned = listOf(shared, other)
        assertEquals(
            emptyList<String>(),
            ownedGrantsToReleaseAfterOwnerRemoval(
                removedUri = shared,
                ownedGrants = owned,
                remainingOwners = listOf(shared),
            ),
        )
        assertEquals(
            listOf(shared),
            ownedGrantsToReleaseAfterOwnerRemoval(
                removedUri = shared,
                ownedGrants = owned,
                remainingOwners = emptyList(),
            ),
        )
    }

    @Test
    fun deferredTreeGrantReleasesAfterLastChildAndKeepsUnrelatedGrant() {
        val tree = "content://provider/tree/root"
        val child = "content://provider/tree/root/document/root%2Fchild.txt"
        val unrelated = "content://other/tree/root"
        assertEquals(
            emptyList<String>(),
            ownedGrantsToReleaseAfterOwnerRemoval(
                removedUri = tree,
                ownedGrants = listOf(tree),
                remainingOwners = listOf(child),
            ),
        )
        assertEquals(
            listOf(tree),
            ownedGrantsToReleaseAfterOwnerRemoval(
                removedUri = child,
                ownedGrants = listOf(tree),
                remainingOwners = emptyList(),
            ),
        )
        assertEquals(
            emptyList<String>(),
            ownedGrantsToReleaseAfterOwnerRemoval(
                removedUri = child,
                ownedGrants = listOf(unrelated),
                remainingOwners = emptyList(),
            ),
        )
    }
}
