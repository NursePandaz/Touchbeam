package dev.beam.classic;

import android.content.SharedPreferences;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Shared durable receive storage for the activity and background receiver. */
final class ReceiveIndex {
    private final File parent;
    private final SharedPreferences prefs;
    ReceiveIndex(File files, SharedPreferences prefs) { parent = new File(files, "received"); this.prefs = prefs; }
    File file() { return child(prefs.getString("file_path", ""), false); }
    File batch() {
        String name = prefs.getString("batch_path", "");
        return name.startsWith("batch-") && name.endsWith(".ready") ? child(name, true) : null;
    }
    private File child(String name, boolean directory) {
        if (name.isEmpty()) return null;
        File file = new File(parent, name);
        try { return (directory ? file.isDirectory() : file.isFile()) && file.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile()) ? file : null; }
        catch (IOException ex) { return null; }
    }
    @android.annotation.SuppressLint("ApplySharedPref") // A receipt requires a durable index; restore the in-memory index on failed commit.
    boolean publish(SharedPreferences.Editor update) {
        String[] keys = {"file_path", "file_name", "file_mime", "file_size", "batch_path", "batch_items"};
        Map<String, ?> previous = new HashMap<>(prefs.getAll());
        if (update.commit()) return true;
        SharedPreferences.Editor rollback = prefs.edit();
        for (String key : keys) {
            Object value = previous.get(key);
            if (value instanceof String) rollback.putString(key, (String)value);
            else if (value instanceof Long) rollback.putLong(key, (Long)value);
            else rollback.remove(key);
        }
        rollback.commit(); return false;
    }
    FileProtocol.Sink fileSink(FileProtocol.Meta meta, BooleanSupplier active, Runnable saved) throws IOException {
        return new StagedFile(parent, (file, result) -> {
            if (!active.getAsBoolean()) throw new IOException("Receiver closed.");
            File old = file(), oldBatch = batch();
            if (!publish(prefs.edit().putString("file_path", file.getName()).putString("file_name", meta.name)
                    .putString("file_mime", meta.mime).putLong("file_size", result.bytes).remove("batch_path").remove("batch_items"))) throw new IOException("Could not save file details.");
            if (old != null && !old.equals(file)) old.delete();
            if (oldBatch != null) StagedBatch.delete(oldBatch);
            saved.run();
        });
    }
    BatchProtocol.Sink batchSink(BatchProtocol.Plan plan, BooleanSupplier active, Runnable saved) throws IOException {
        return new StagedBatch(parent, plan.files.size(), (directory, results) -> {
            if (!active.getAsBoolean()) throw new IOException("Receiver closed.");
            JSONArray manifest = new JSONArray();
            try { for (int i = 0; i < plan.files.size(); i++) manifest.put(new JSONObject().put("name", plan.files.get(i).name).put("mime", plan.files.get(i).mime).put("size", results.get(i).bytes)); }
            catch (JSONException ex) { throw new IOException("Could not record received files.", ex); }
            File old = file(), oldBatch = batch();
            if (!publish(prefs.edit().putString("batch_path", directory.getName()).putString("batch_items", manifest.toString())
                    .remove("file_path").remove("file_name").remove("file_mime").remove("file_size"))) throw new IOException("Could not save batch details.");
            if (old != null) old.delete(); if (oldBatch != null) StagedBatch.delete(oldBatch);
            saved.run();
        });
    }
}
