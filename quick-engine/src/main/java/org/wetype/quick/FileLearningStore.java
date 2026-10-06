package org.wetype.quick;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Checksummed, bounded app-private binary storage with write/backup/rename recovery.
 * Keep one instance per file. The host must pass an app-private no-backup directory;
 * this class neither grants permissions nor encrypts data nor prevents host app telemetry.
 */
public final class FileLearningStore implements LearningStore {
    private static final int MAGIC = 0x514c5331;
    private static final int MAX_BYTES = 1024 * 1024;
    private final File file, temp, backup;

    public FileLearningStore(File file) {
        if (file == null) throw new NullPointerException("file");
        this.file = file;
        temp = new File(file.getPath() + ".new");
        backup = new File(file.getPath() + ".bak");
    }
    /** A missing file is an empty profile. Corrupt files throw, never silently enable learning. */
    @Override public synchronized Map<String, SelectionStat> load() throws IOException {
        if (file.exists()) {
            try { return readFile(file); }
            catch (IOException e) {
                if (backup.exists()) return readFile(backup);
                throw e;
            }
        }
        if (backup.exists()) return readFile(backup);
        return new HashMap<String, SelectionStat>();
    }
    private Map<String, SelectionStat> readFile(File source) throws IOException {
        if (source.length() > MAX_BYTES) throw new IOException("Learning file too large");
        DataInputStream in = new DataInputStream(new FileInputStream(source));
        byte[] payload;
        int version;
        try {
            if (in.readInt() != MAGIC) throw new IOException("Unknown learning file format");
            version = in.readInt();
            if (version != 1 && version != 2) throw new IOException("Unknown learning file format");
            int length = in.readInt();
            if (length < 4 || length > MAX_BYTES - 20) throw new IOException("Invalid learning file length");
            payload = new byte[length]; in.readFully(payload);
            long expected = in.readLong();
            CRC32 crc = new CRC32(); crc.update(payload);
            if (crc.getValue() != expected || in.read() != -1) throw new IOException("Learning file checksum mismatch");
        } finally { in.close(); }
        DataInputStream entries = new DataInputStream(new ByteArrayInputStream(payload));
        int count = entries.readInt();
        if (count < 0 || count > (version == 1 ? QuickEngine.MAX_LEARNED_ENTRIES : CompositionEngine.MAX_STORED_ENTRIES)) throw new IOException("Invalid learning entry count");
        Map<String, SelectionStat> result = new HashMap<String, SelectionStat>();
        for (int i = 0; i < count; i++) {
            String key = entries.readUTF();
            long selections = entries.readLong(), sequence = entries.readLong();
            if (!(version == 1 ? QuickEngine.validKey(key) : CompositionEngine.validStoredKey(key)) || selections < 1 || sequence < 1 || result.containsKey(key))
                throw new IOException("Invalid learning entry");
            result.put(key, new SelectionStat(selections, sequence));
        }
        if (entries.read() != -1) throw new IOException("Unexpected learning payload");
        return result;
    }
    @Override public synchronized void save(Map<String, SelectionStat> snapshot) throws IOException {
        if (snapshot == null || snapshot.size() > CompositionEngine.MAX_STORED_ENTRIES)
            throw new IOException("Learning snapshot too large");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(snapshot.size());
        for (Map.Entry<String, SelectionStat> entry : new TreeMap<String, SelectionStat>(snapshot).entrySet()) {
            if (!CompositionEngine.validStoredKey(entry.getKey()) || entry.getValue() == null)
                throw new IOException("Invalid learning entry");
            data.writeUTF(entry.getKey()); data.writeLong(entry.getValue().count); data.writeLong(entry.getValue().sequence);
        }
        data.flush();
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_BYTES - 20) throw new IOException("Learning payload too large");
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Cannot create local learning directory");
        CRC32 crc = new CRC32(); crc.update(payload);
        FileOutputStream output = new FileOutputStream(temp);
        DataOutputStream out = new DataOutputStream(output);
        try {
            out.writeInt(MAGIC); out.writeInt(2); out.writeInt(payload.length);
            out.write(payload); out.writeLong(crc.getValue()); out.flush(); output.getFD().sync();
        } finally { out.close(); }
        // Preserve the previous profile until the durable new snapshot is ready to swap.
        if (backup.exists()) {
            boolean primaryValid = false;
            if (file.exists()) {
                try { readFile(file); primaryValid = true; }
                catch (IOException ignored) { /* Keep the known recovery file during replacement. */ }
            }
            if (primaryValid) {
                if (!backup.delete()) throw new IOException("Cannot replace learning backup");
            } else if (file.exists() && !file.delete()) {
                throw new IOException("Cannot replace corrupt learning profile");
            }
        }
        if (file.exists() && !file.renameTo(backup)) throw new IOException("Cannot back up learning profile");
        if (!temp.renameTo(file)) {
            if (backup.exists() && !backup.renameTo(file)) throw new IOException("Cannot save learning profile; backup preserved");
            throw new IOException("Cannot save learning profile");
        }
        // A backup containing learned choices must not survive an explicit clear.
        if (backup.exists() && !backup.delete()) throw new IOException("Saved new profile but could not remove previous backup");
    }
}
