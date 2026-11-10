package com.muguang.album.integration;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Platform-only activity executing in the fixture provider APK's own UID. */
public final class SyntheticGrantActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean revoke = getIntent().getBooleanExtra("revoke", false);
        Uri[] folders = {TestDocumentsProvider.PHOTOS_TREE, TestDocumentsProvider.MUSIC_TREE};
        try {
            for (Uri folder : folders) {
                if (revoke) revokeUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                else grantUriPermission("com.muguang.album", folder,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            }
        } finally {
            finish();
        }
    }
}
