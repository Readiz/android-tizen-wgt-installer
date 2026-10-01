package dev.readiz.wgtinstaller;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Test APK only; plain Java so the provider can also run outside instrumentation. */
public class FixtureDocumentProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) { return "application/octet-stream"; }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new String[]{"fixture.wgt"});
        return cursor;
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!mode.equals("r")) throw new FileNotFoundException("read only");
        File file = new File(getContext().getCacheDir(), "picker-fixture.wgt");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("config.xml"));
            zip.write(("<widget xmlns=\"http://www.w3.org/ns/widgets\" xmlns:tizen=\"http://tizen.org/ns/widgets\" version=\"1.0\"><tizen:application id=\"Fixture001.App\" package=\"Fixture001\"/><name>Picker fixture</name></widget>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("index.html"));
            zip.write("fixture only".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
