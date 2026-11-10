package com.muguang.album.integration;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Platform-only fixture provider. Its separate APK process intentionally requires neither
 * the target app's Kotlin runtime nor AndroidX. All documents are synthetic and read-only.
 */
public final class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.muguang.album.integration.synthetic.documents";
    public static final Uri PHOTOS_TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "photos");
    public static final Uri MUSIC_TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "music");

    private static final String[] PHOTO_IDS = {"photo-1", "photo-2", "photo-3"};
    private static final String[] MUSIC_IDS = {"music-1", "music-2"};
    private static final String[] DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    private static final String[] ROOT_COLUMNS = {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        addRoot(result, "photos", "合成照片");
        addRoot(result, "music", "静音配乐");
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addDocument(result, documentId);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        String[] children;
        if ("photos".equals(parentDocumentId)) children = PHOTO_IDS;
        else if ("music".equals(parentDocumentId)) children = MUSIC_IDS;
        else throw new FileNotFoundException("Not a synthetic fixture folder: " + parentDocumentId);
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        for (String child : children) addDocument(result, child);
        return result;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return parentDocumentId.equals(documentId)
                || ("photos".equals(parentDocumentId) && contains(PHOTO_IDS, documentId))
                || ("music".equals(parentDocumentId) && contains(MUSIC_IDS, documentId));
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Synthetic fixtures are read-only");
        if (signal != null) signal.throwIfCanceled();
        return ParcelFileDescriptor.open(fixtureFile(documentId), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void addRoot(MatrixCursor cursor, String id, String title) {
        Map<String, Object> values = new HashMap<>();
        values.put(DocumentsContract.Root.COLUMN_ROOT_ID, id);
        values.put(DocumentsContract.Root.COLUMN_DOCUMENT_ID, id);
        values.put(DocumentsContract.Root.COLUMN_TITLE, title);
        values.put(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        values.put(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_gallery);
        values.put(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, 0L);
        addValues(cursor, values);
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean directory = "photos".equals(id) || "music".equals(id);
        String name;
        switch (id) {
            case "photos": name = "合成测试照片"; break;
            case "music": name = "静音测试音乐"; break;
            case "photo-1": name = "01-合成蓝色.jpg"; break;
            case "photo-2": name = "02-合成紫色.jpg"; break;
            case "photo-3": name = "03-合成绿色.jpg"; break;
            case "music-1": name = "01-silence.wav"; break;
            case "music-2": name = "02-silence.wav"; break;
            default: throw new FileNotFoundException("Unknown synthetic fixture: " + id);
        }
        Map<String, Object> values = new HashMap<>();
        values.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name);
        values.put(DocumentsContract.Document.COLUMN_MIME_TYPE, directory
                ? DocumentsContract.Document.MIME_TYPE_DIR
                : contains(PHOTO_IDS, id) ? "image/jpeg" : "audio/wav");
        values.put(DocumentsContract.Document.COLUMN_FLAGS, 0);
        values.put(DocumentsContract.Document.COLUMN_SIZE, directory ? 0L : fixtureFile(id).length());
        values.put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 1_700_000_000_000L);
        addValues(cursor, values);
    }

    private static void addValues(MatrixCursor cursor, Map<String, Object> values) {
        String[] columns = cursor.getColumnNames();
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) row[index] = values.get(columns[index]);
        cursor.addRow(row);
    }

    private synchronized File fixtureFile(String id) throws FileNotFoundException {
        boolean photo = contains(PHOTO_IDS, id);
        if (!photo && !contains(MUSIC_IDS, id)) throw new FileNotFoundException("Unknown synthetic fixture");
        File folder = new File(getContext().getCacheDir(), "muguang-synthetic-fixtures");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new FileNotFoundException("Cannot create fixture cache");
        File file = new File(folder, id + (photo ? ".jpg" : ".wav"));
        if (file.isFile() && file.length() > 0) return file;
        try {
            if (photo) writePhoto(file, id);
            else writeSilentWav(file);
        } catch (IOException failure) {
            FileNotFoundException wrapped = new FileNotFoundException("Cannot create synthetic fixture: " + id);
            wrapped.initCause(failure);
            throw wrapped;
        }
        return file;
    }

    private static void writePhoto(File file, String id) throws IOException {
        int background = Color.rgb(54, 81, 118);
        if ("photo-2".equals(id)) background = Color.rgb(117, 76, 133);
        else if ("photo-3".equals(id)) background = Color.rgb(59, 111, 91);
        Bitmap bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(background);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.rgb(230, 224, 233));
            canvas.drawCircle(350f, 85f, 38f, paint);
            paint.setColor(Color.rgb(32, 40, 57));
            canvas.drawRect(0f, 220f, 480f, 320f, paint);
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    throw new IOException("JPEG encoding failed");
                }
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static void writeSilentWav(File file) throws IOException {
        int sampleRate = 8000;
        int dataBytes = sampleRate * 30 * 2;
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            output.writeBytes("RIFF");
            writeLittleInt(output, 36 + dataBytes);
            output.writeBytes("WAVEfmt ");
            writeLittleInt(output, 16);
            writeLittleShort(output, 1);
            writeLittleShort(output, 1);
            writeLittleInt(output, sampleRate);
            writeLittleInt(output, sampleRate * 2);
            writeLittleShort(output, 2);
            writeLittleShort(output, 16);
            output.writeBytes("data");
            writeLittleInt(output, dataBytes);
            byte[] zeros = new byte[8000];
            for (int remaining = dataBytes; remaining > 0; remaining -= zeros.length) {
                output.write(zeros, 0, Math.min(remaining, zeros.length));
            }
        }
    }

    private static void writeLittleInt(DataOutputStream output, int value) throws IOException {
        for (int index = 0; index < 4; index++) output.writeByte(value >>> (index * 8));
    }

    private static void writeLittleShort(DataOutputStream output, int value) throws IOException {
        output.writeByte(value);
        output.writeByte(value >>> 8);
    }

    private static boolean contains(String[] values, String candidate) {
        for (String value : values) if (value.equals(candidate)) return true;
        return false;
    }
}
