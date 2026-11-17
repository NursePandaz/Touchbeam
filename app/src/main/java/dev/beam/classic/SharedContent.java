package dev.beam.classic;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Parse a share without opening its URIs or sending anything automatically. */
public final class SharedContent {
    public static final int MAX_ITEMS = 32;
    public final List<Uri> files;
    public final String text, mime;
    private SharedContent(List<Uri> files, String text, String mime) {
        this.files = Collections.unmodifiableList(files); this.text = text; this.mime = mime;
    }
    public static boolean isShare(Intent intent) {
        return intent != null && (Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()));
    }
    public static SharedContent read(Intent intent) throws IOException {
        if (!isShare(intent)) return null;
        try {
            LinkedHashSet<Uri> files = new LinkedHashSet<>();
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                ArrayList<?> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (streams != null) {
                    if (streams.size() > MAX_ITEMS) throw new IOException("Share up to 32 files in one beam.");
                    for (Object stream : streams) add(files, stream);
                }
            } else {
                Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream != null) add(files, stream);
            }
            ClipData clips = intent.getClipData();
            if (clips != null) {
                if (clips.getItemCount() > MAX_ITEMS) throw new IOException("Share up to 32 files in one beam.");
                for (int i = 0; i < clips.getItemCount(); i++) {
                    Uri uri = clips.getItemAt(i).getUri();
                    if (uri != null) add(files, uri);
                }
            }
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text == null && files.isEmpty() && clips != null && clips.getItemCount() > 0) text = clips.getItemAt(0).getText();
            String plain = text == null ? null : text.toString();
            if (plain != null && plain.getBytes(StandardCharsets.UTF_8).length > Wire.MAX_BYTES) {
                if (files.isEmpty()) throw new IOException("Shared text exceeds 16 KB. Share it as a file instead.");
                // The file is still usable; do not silently truncate an oversized caption.
                throw new IOException("The shared caption exceeds 16 KB. Share the file without this caption.");
            }
            if (files.isEmpty() && (plain == null || plain.trim().isEmpty())) throw new IOException("This share contains no accessible file or text. Try Choose file in Touchbeam.");
            return new SharedContent(new ArrayList<>(files), plain, intent.getType());
        } catch (RuntimeException ex) { throw new IOException("Could not read this share. Try Choose file in Touchbeam.", ex); }
    }
    private static void add(Set<Uri> files, Object value) throws IOException {
        if (!(value instanceof Uri)) throw new IOException("This app did not share a valid file link.");
        Uri uri = (Uri)value;
        if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null || uri.getAuthority().isEmpty())
            throw new IOException("Only Android content links are accepted. Try Choose file in Touchbeam.");
        files.add(uri);
        if (files.size() > MAX_ITEMS) throw new IOException("Share up to 32 files in one beam.");
    }
}
