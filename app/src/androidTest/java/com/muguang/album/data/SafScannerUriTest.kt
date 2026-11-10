package com.muguang.album.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafScannerUriTest {
    @Test fun acceptsApi21TreeAndTreeDocumentUris() {
        assertTrue(SafScanner.isTreeUriCompat(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures")))
        assertTrue(SafScanner.isTreeUriCompat(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures/document/primary%3APictures%2F1.jpg")))
    }

    @Test fun rejectsIndividualDocumentsAndEmptyTreeIds() {
        assertFalse(SafScanner.isTreeUriCompat(Uri.parse("content://com.android.externalstorage.documents/document/primary%3APictures")))
        assertFalse(SafScanner.isTreeUriCompat(Uri.parse("content://com.android.externalstorage.documents/tree/")))
        assertFalse(SafScanner.isTreeUriCompat(Uri.parse("content://com.android.externalstorage.documents/")))
    }

    @Test fun rejectsNonContentOrAuthoritylessUris() {
        assertFalse(SafScanner.isTreeUriCompat(Uri.parse("file:///tree/pictures")))
        assertFalse(SafScanner.isTreeUriCompat(Uri.parse("content:///tree/pictures")))
    }
}
