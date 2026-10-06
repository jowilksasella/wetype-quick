package org.wetype.quick.integration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;
import org.wetype.quick.CompositionEngine;

/**
 * Local, explicit candidate pin/block choices, independent of the native engine's IDs.
 * The caller must verify dictionary membership and suppress actions in private mode.
 * Store one instance in an app-private, no-backup file; no cross-process lock is taken.
 * Nothing here records typing history, surrounding text, timestamps or network data.
 *
 * Mutations are synchronized and memory-only, except clear(). Call save()/clear() on
 * the controller's storage executor. They serialize I/O separately from short state
 * locks, so candidate reads do not wait for disk. A mutation during a save remains dirty.
 */
public final class QuickLocalActions {
    public static final int MAX_ENTRIES = 4096;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAGIC = 0x51414331; // QAC1, separate from selection learning.
    private static final int VERSION = 1;
    private static final int PIN = 1, BLOCK = 2;

    private final File file, temp, backup;
    private final Object ioLock = new Object();
    private final Map<String, String> pins = new TreeMap<String, String>();
    private final Set<String> blocks = new TreeSet<String>();
    private final boolean recoveredFromBackup;
    private Object generation = new Object();
    private Object savedGeneration = generation;

    private QuickLocalActions(File file, Snapshot snapshot, boolean recovered) {
        this.file = file;
        temp = new File(file.getPath() + ".new");
        backup = new File(file.getPath() + ".bak");
        pins.putAll(snapshot.pins);
        blocks.addAll(snapshot.blocks);
        recoveredFromBackup = recovered;
    }

    /**
     * Missing files mean no choices. Only a fully validated backup may recover a bad
     * or missing primary; callers can disclose that recovery via recoveredFromBackup().
     * Unrecoverable corruption/I/O throws, rather than silently replacing user choices.
     * An orphan .new is an uncommitted write and is never used as a profile.
     */
    public static QuickLocalActions load(File noBackupFile) throws IOException {
        if (noBackupFile == null) throw new NullPointerException("noBackupFile");
        File primary = noBackupFile.getAbsoluteFile();
        File recovery = new File(primary.getPath() + ".bak");
        IOException primaryFailure = null;
        if (primary.exists()) {
            try { return new QuickLocalActions(primary, readFile(primary), false); }
            catch (IOException failure) { primaryFailure = failure; }
        }
        if (recovery.exists()) {
            try { return new QuickLocalActions(primary, readFile(recovery), true); }
            catch (IOException failure) {
                if (primaryFailure == null) throw failure;
                primaryFailure.addSuppressed(failure);
            }
        }
        if (primaryFailure != null) throw primaryFailure;
        return new QuickLocalActions(primary, new Snapshot(), false);
    }

    /** Reports how this instance loaded, not whether later saves succeeded. */
    public boolean recoveredFromBackup() { return recoveredFromBackup; }

    /** Invalid/empty queries have no local action. */
    public synchronized String pinned(String query) {
        return pins.get(CompositionEngine.normalizeQuery(query));
    }

    public synchronized boolean isBlocked(String query, String text) {
        String normalized = CompositionEngine.normalizeQuery(query);
        return normalized.length() != 0 && validText(text)
            && blocks.contains(key(normalized, text));
    }

    /** One pin per canonical query. Pinning unblocks that same candidate. */
    public synchronized boolean pin(String query, String text) {
        String normalized = requireQuery(query);
        requireText(text);
        String blockedKey = key(normalized, text);
        boolean wasBlocked = blocks.contains(blockedKey);
        String previous = pins.get(normalized);
        if (text.equals(previous) && !wasBlocked) return false;
        checkCapacity(entryCount() + (previous == null ? 1 : 0) - (wasBlocked ? 1 : 0));
        blocks.remove(blockedKey);
        pins.put(normalized, text);
        changed();
        return true;
    }

    public synchronized boolean unpin(String query) {
        if (pins.remove(requireQuery(query)) == null) return false;
        changed();
        return true;
    }

    /** Blocking is scoped to this query and unpins that same candidate. */
    public synchronized boolean block(String query, String text) {
        String normalized = requireQuery(query);
        requireText(text);
        String blockedKey = key(normalized, text);
        boolean existed = blocks.contains(blockedKey);
        boolean wasPinned = text.equals(pins.get(normalized));
        if (existed && !wasPinned) return false;
        checkCapacity(entryCount() + (existed ? 0 : 1) - (wasPinned ? 1 : 0));
        if (wasPinned) pins.remove(normalized);
        blocks.add(blockedKey);
        changed();
        return true;
    }

    public synchronized boolean unblock(String query, String text) {
        String normalized = requireQuery(query);
        requireText(text);
        if (!blocks.remove(key(normalized, text))) return false;
        changed();
        return true;
    }

    public synchronized int entryCount() { return pins.size() + blocks.size(); }
    public synchronized boolean hasUnsavedChanges() { return generation != savedGeneration; }

    private void changed() { generation = new Object(); }

    private static void checkCapacity(int count) {
        if (count > MAX_ENTRIES)
            throw new IllegalStateException("Local candidate action limit reached");
    }

    /** Write even a clean snapshot, allowing repair after backup recovery. */
    public void save() throws IOException {
        synchronized (ioLock) {
            Snapshot snapshot;
            synchronized (this) { snapshot = snapshot(); }
            writeFile(snapshot);
            synchronized (this) { savedGeneration = snapshot.generation; }
        }
    }

    /**
     * Erase active choices and persist an empty profile, including removal of the backup.
     * On IOException active memory is empty but remains dirty; disk erasure is NOT
     * confirmed and the host must report the failure and retry save()/clear().
     * A later concurrent mutation is a new action and remains dirty for a later save.
     */
    public void clear() throws IOException {
        synchronized (ioLock) {
            Snapshot empty;
            synchronized (this) {
                pins.clear();
                blocks.clear();
                changed();
                empty = snapshot();
            }
            writeFile(empty);
            synchronized (this) { savedGeneration = empty.generation; }
        }
    }

    private Snapshot snapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.pins.putAll(pins);
        snapshot.blocks.addAll(blocks);
        snapshot.generation = generation;
        return snapshot;
    }

    private static final class Snapshot {
        final Map<String, String> pins = new TreeMap<String, String>();
        final Set<String> blocks = new TreeSet<String>();
        Object generation;
        int size() { return pins.size() + blocks.size(); }
    }

    private static String key(String query, String text) { return query + '\t' + text; }

    private static String requireQuery(String query) {
        if (!CompositionEngine.isValidQuery(query))
            throw new IllegalArgumentException("Invalid Quick query");
        return CompositionEngine.normalizeQuery(query);
    }

    private static void requireText(String text) {
        if (!validText(text)) throw new IllegalArgumentException("Invalid Quick candidate text");
    }

    private static boolean validText(String text) {
        if (text == null || text.length() == 0 || text.length() > 16) return false;
        int count = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (++count > CompositionEngine.MAX_CHARACTERS || (cp >= 0xd800 && cp <= 0xdfff)
                || Character.isISOControl(cp) || Character.isWhitespace(cp)
                || Character.isSpaceChar(cp) || Character.getType(cp) == Character.FORMAT
                || (cp & 0xffff) >= 0xfffe || (cp >= 0xfdd0 && cp <= 0xfdef)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static Snapshot readFile(File source) throws IOException {
        if (!source.isFile() || source.length() > MAX_BYTES)
            throw new IOException("Invalid local candidate action file");
        byte[] payload;
        try (DataInputStream in = new DataInputStream(new FileInputStream(source))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION)
                throw new IOException("Unknown local candidate action format");
            int length = in.readInt();
            if (length < 4 || length > MAX_BYTES - 20)
                throw new IOException("Invalid local candidate action length");
            payload = new byte[length];
            in.readFully(payload);
            long expected = in.readLong();
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (expected != crc.getValue() || in.read() != -1)
                throw new IOException("Local candidate action checksum mismatch");
        }
        Snapshot result = new Snapshot();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES)
                throw new IOException("Invalid local candidate action count");
            for (int i = 0; i < count; i++) {
                int type = in.readUnsignedByte();
                String query = in.readUTF(), text = in.readUTF();
                if (!CompositionEngine.isValidQuery(query)
                    || !query.equals(CompositionEngine.normalizeQuery(query)) || !validText(text))
                    throw new IOException("Invalid local candidate action entry");
                if (type == PIN) {
                    if (result.pins.put(query, text) != null)
                        throw new IOException("Duplicate local candidate pin");
                } else if (type == BLOCK) {
                    if (!result.blocks.add(key(query, text)))
                        throw new IOException("Duplicate local candidate block");
                } else throw new IOException("Unknown local candidate action");
            }
            if (in.read() != -1) throw new IOException("Unexpected local candidate action payload");
        }
        for (Map.Entry<String, String> pin : result.pins.entrySet()) {
            if (result.blocks.contains(key(pin.getKey(), pin.getValue())))
                throw new IOException("Conflicting local candidate actions");
        }
        return result;
    }

    private void writeFile(Snapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(snapshot.size());
        for (Map.Entry<String, String> pin : snapshot.pins.entrySet()) {
            data.writeByte(PIN);
            data.writeUTF(pin.getKey());
            data.writeUTF(pin.getValue());
        }
        for (String blocked : snapshot.blocks) {
            int separator = blocked.indexOf('\t');
            data.writeByte(BLOCK);
            data.writeUTF(blocked.substring(0, separator));
            data.writeUTF(blocked.substring(separator + 1));
        }
        data.flush();
        byte[] payload = bytes.toByteArray();
        if (snapshot.size() > MAX_ENTRIES || payload.length > MAX_BYTES - 20)
            throw new IOException("Local candidate action snapshot too large");
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
            throw new IOException("Cannot create local candidate action directory");
        if ((file.exists() && !file.isFile()) || (backup.exists() && !backup.isFile())
            || (temp.exists() && !temp.isFile()))
            throw new IOException("Local candidate action path is not a file");
        CRC32 crc = new CRC32();
        crc.update(payload);
        try (FileOutputStream output = new FileOutputStream(temp);
             DataOutputStream out = new DataOutputStream(output)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(payload.length);
            out.write(payload);
            out.writeLong(crc.getValue());
            out.flush();
            output.getFD().sync();
        }
        // A fully written, fsynced temp is now available. Preserve a valid old copy
        // across renames, including when loading recovered from a corrupt primary.
        if (backup.exists()) {
            boolean primaryValid = false;
            if (file.exists()) {
                try { readFile(file); primaryValid = true; }
                catch (IOException ignored) { /* Keep the recovery copy until replacement. */ }
            }
            if (primaryValid) {
                if (!backup.delete()) throw new IOException("Cannot replace local candidate action backup");
            } else if (file.exists() && !file.delete()) {
                throw new IOException("Cannot replace corrupt local candidate action file");
            }
        }
        if (file.exists() && !file.renameTo(backup))
            throw new IOException("Cannot back up local candidate actions");
        if (!temp.renameTo(file)) {
            if (backup.exists() && !backup.renameTo(file))
                throw new IOException("Cannot save local candidate actions; backup preserved");
            throw new IOException("Cannot save local candidate actions");
        }
        // Old blocked/pinned text must not survive a successful explicit clear.
        if (backup.exists() && !backup.delete())
            throw new IOException("Saved local candidate actions but could not remove old backup");
        // Same-directory rename is recoverable, but Java 8 cannot portably fsync the
        // directory: power loss can still lose the latest update. This is not encryption.
    }
}
