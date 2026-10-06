package org.wetype.quick.integration;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Pure Java 8 storage/policy regression tests; no Android or proprietary classes. */
public final class QuickLocalActionsTest {
    private static int assertions;
    private static final String NON_BMP = new String(Character.toChars(0x20000));

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("quick-local-actions-tests-");
        try {
            roundTrip(root.resolve("roundtrip.bin").toFile());
            pinBlockPolicy(root.resolve("policy.bin").toFile());
            validation(root.resolve("validation.bin").toFile());
            capacity(root.resolve("capacity.bin").toFile());
            corruptionAndRecovery(root.resolve("recovery.bin").toFile());
            malformedSnapshots(root.resolve("malformed.bin").toFile());
            clearAndRetry(root.resolve("clear.bin").toFile());
            orphanTemp(root.resolve("orphan.bin").toFile());
            System.out.println("PASS: QuickLocalActionsTest (" + assertions + " assertions)");
        } finally {
            deleteTree(root.toFile());
        }
    }

    private static void roundTrip(File file) throws Exception {
        QuickLocalActions actions = QuickLocalActions.load(file);
        equal(0, actions.entryCount(), "missing profile is empty");
        check(!actions.hasUnsavedChanges(), "missing profile starts clean");
        check(actions.pin("ENYM", "測試"), "explicit pin changes memory");
        check(actions.block("ab", NON_BMP), "non-BMP candidate is one code point");
        check(!file.exists(), "ordinary mutations do not write synchronously");
        check(actions.hasUnsavedChanges(), "mutations stay dirty before save");
        actions.save();
        check(!actions.hasUnsavedChanges(), "successful save clears dirty state");
        check(!new File(file + ".bak").exists(), "successful save removes old recovery data");
        check(!new File(file + ".new").exists(), "successful save consumes temp");
        QuickLocalActions loaded = QuickLocalActions.load(file);
        equal("測試", loaded.pinned("en'ym"), "canonical query shares pin");
        check(loaded.isBlocked("AB", NON_BMP), "non-BMP block round trips");
        equal(2, loaded.entryCount(), "both action types round trip");
        check(!loaded.recoveredFromBackup(), "normal load does not claim recovery");
        check(loaded.unpin("enym"), "unpin changes loaded data");
        check(loaded.unblock("ab", NON_BMP), "unblock changes loaded data");
        loaded.save();
        equal(0, QuickLocalActions.load(file).entryCount(), "unpin/unblock persist");
    }

    private static void pinBlockPolicy(File file) throws Exception {
        QuickLocalActions actions = QuickLocalActions.load(file);
        actions.pin("enym", "測試");
        check(!actions.pin("en'ym'", "測試"), "equivalent pin is idempotent");
        actions.block("enym", "測試");
        equal(null, actions.pinned("enym"), "blocking removes same pin");
        check(actions.isBlocked("enym", "測試"), "candidate is blocked");
        equal(1, actions.entryCount(), "block replaces same pin");
        check(!actions.block("ENYM", "測試"), "equivalent block is idempotent");
        actions.pin("enym", "測試");
        check(!actions.isBlocked("enym", "測試"), "pinning removes same block");
        equal(1, actions.entryCount(), "pin replaces same block");
        actions.block("enym", "文字");
        equal("測試", actions.pinned("enym"), "other block preserves pin");
        actions.pin("enym", "文字");
        equal("文字", actions.pinned("enym"), "new pin replaces old query pin");
        equal(1, actions.entryCount(), "pinning blocked text removes block and old pin");
        actions.block("a", "日");
        check(!actions.isBlocked("a'", "日"), "prefix and exact-root queries are distinct");
        actions.pin("a'", "日");
        check(actions.isBlocked("a", "日"), "exact-root pin does not alter prefix block");
        check(!actions.unpin("ab"), "missing unpin is harmless");
        check(!actions.unblock("ab", "日"), "missing unblock is harmless");
    }

    private static void validation(File file) throws Exception {
        final QuickLocalActions actions = QuickLocalActions.load(file);
        String[] invalidQueries = {null, "", "z", "é", "a b", "'a", "a''b", "aaaaaaaaaaaaaaaaa"};
        for (final String query : invalidQueries) {
            expect(IllegalArgumentException.class, new Checked() {
                @Override public void run() { actions.pin(query, "日"); }
            }, "reject invalid query");
            equal(null, actions.pinned(query), "invalid query read returns no pin");
            check(!actions.isBlocked(query, "日"), "invalid query read returns no block");
        }
        String[] invalidTexts = {null, "", "a\tb", "\n", " ", "\u00a0", "\u0085",
            "\u200d", "\ud800", "\udc00", "\uffff", "一二三四五六七八九"};
        for (final String text : invalidTexts) {
            expect(IllegalArgumentException.class, new Checked() {
                @Override public void run() { actions.block("a", text); }
            }, "reject malformed candidate text");
            check(!actions.isBlocked("a", text), "malformed text read returns no block");
        }
        equal(0, actions.entryCount(), "rejected inputs never mutate state");
        String eightNonBmp = NON_BMP + NON_BMP + NON_BMP + NON_BMP
            + NON_BMP + NON_BMP + NON_BMP + NON_BMP;
        actions.pin("aaaaaaaaaaaaaaaa", eightNonBmp);
        equal(eightNonBmp, actions.pinned("aa'aa'aa'aa'aa'aa'aa'aa"), "eight Unicode code points accepted");
        actions.save();
        equal(eightNonBmp, QuickLocalActions.load(file).pinned("aaaaaaaaaaaaaaaa"), "max-size valid query/text reload");
    }

    private static void capacity(File file) throws Exception {
        final QuickLocalActions actions = QuickLocalActions.load(file);
        for (int i = 0; i < QuickLocalActions.MAX_ENTRIES; i++)
            actions.block("a", new String(Character.toChars(0x4e00 + i)));
        equal(QuickLocalActions.MAX_ENTRIES, actions.entryCount(), "cap can be filled");
        expect(IllegalStateException.class, new Checked() {
            @Override public void run() { actions.block("b", "日"); }
        }, "cap rejects a new block");
        expect(IllegalStateException.class, new Checked() {
            @Override public void run() { actions.pin("b", "日"); }
        }, "cap rejects a new pin");
        check(!actions.isBlocked("b", "日"), "rejected block is atomic");
        equal(null, actions.pinned("b"), "rejected pin is atomic");
        actions.pin("a", "一");
        equal(QuickLocalActions.MAX_ENTRIES, actions.entryCount(), "block-to-pin replacement allowed at cap");
        actions.block("a", "一");
        equal(QuickLocalActions.MAX_ENTRIES, actions.entryCount(), "pin-to-block replacement allowed at cap");
        actions.save();
        check(file.length() < 1024 * 1024, "maximum snapshot stays below byte cap");
        QuickLocalActions loaded = QuickLocalActions.load(file);
        equal(QuickLocalActions.MAX_ENTRIES, loaded.entryCount(), "full profile reloads");
        check(loaded.unblock("a", "一"), "unblock frees capacity");
        check(loaded.pin("b", "日"), "free slot accepts a pin");
    }

    private static void corruptionAndRecovery(final File file) throws Exception {
        QuickLocalActions actions = QuickLocalActions.load(file);
        actions.pin("a", "日");
        actions.save();
        byte[] original = Files.readAllBytes(file.toPath());
        byte[] corrupt = original.clone();
        corrupt[15] ^= 1;
        Files.write(file.toPath(), corrupt);
        expect(IOException.class, new Checked() {
            @Override public void run() throws Exception { QuickLocalActions.load(file); }
        }, "CRC mismatch without backup is surfaced");
        File backup = new File(file + ".bak");
        Files.write(backup.toPath(), original);
        QuickLocalActions recovered = QuickLocalActions.load(file);
        check(recovered.recoveredFromBackup(), "valid backup recovery is surfaced");
        equal("日", recovered.pinned("a"), "CRC corruption recovers only validated backup");
        recovered.pin("b", "月");
        recovered.save();
        check(!backup.exists(), "repair removes old backup after successful install");
        equal("月", QuickLocalActions.load(file).pinned("b"), "recovered state can save updates");
        Files.write(backup.toPath(), Files.readAllBytes(file.toPath()));
        check(file.delete(), "simulate interrupted primary rename");
        recovered = QuickLocalActions.load(file);
        check(recovered.recoveredFromBackup(), "missing primary uses valid backup");
        equal("月", recovered.pinned("b"), "missing primary recovery retains actions");
        Files.write(file.toPath(), corrupt);
        Files.write(backup.toPath(), new byte[] {1, 2, 3});
        try {
            QuickLocalActions.load(file);
            throw new AssertionError("both corrupt files must fail");
        } catch (IOException expected) {
            equal(1, expected.getSuppressed().length, "both read failures remain diagnosable");
        }
    }

    private static void malformedSnapshots(final File file) throws Exception {
        byte[][] invalid = {
            encoded(4097),
            encoded(-1),
            encoded(1, 1, "AB", "日"),
            encoded(1, 1, "z", "日"),
            encoded(1, 3, "a", "日"),
            encoded(1, 1, "a", "\ud800"),
            encoded(2, 1, "a", "日", 1, "a", "月"),
            encoded(2, 2, "a", "日", 2, "a", "日"),
            encoded(2, 1, "a", "日", 2, "a", "日"),
            encoded(0, 1, "a", "日")
        };
        for (byte[] bytes : invalid) {
            Files.write(file.toPath(), bytes);
            expect(IOException.class, new Checked() {
                @Override public void run() throws Exception { QuickLocalActions.load(file); }
            }, "semantically invalid checksummed profile is rejected");
        }
        byte[] valid = encoded(1, 1, "a", "日");
        Files.write(file.toPath(), Arrays.copyOf(valid, valid.length - 1));
        expect(IOException.class, new Checked() {
            @Override public void run() throws Exception { QuickLocalActions.load(file); }
        }, "truncated profile is rejected");
        Files.write(file.toPath(), Arrays.copyOf(valid, valid.length + 1));
        expect(IOException.class, new Checked() {
            @Override public void run() throws Exception { QuickLocalActions.load(file); }
        }, "trailing bytes are rejected");
        Files.write(file.toPath(), new byte[1024 * 1024 + 1]);
        expect(IOException.class, new Checked() {
            @Override public void run() throws Exception { QuickLocalActions.load(file); }
        }, "oversized file is rejected before allocating payload");
    }

    private static void clearAndRetry(final File file) throws Exception {
        final QuickLocalActions actions = QuickLocalActions.load(file);
        actions.pin("a", "日");
        actions.block("b", "月");
        actions.save();
        File backup = new File(file + ".bak");
        Files.write(backup.toPath(), Files.readAllBytes(file.toPath()));
        actions.clear();
        equal(0, actions.entryCount(), "clear resets active state");
        equal(0, QuickLocalActions.load(file).entryCount(), "clear persists an empty profile");
        check(!actions.hasUnsavedChanges(), "successful clear is clean");
        check(!backup.exists(), "clear removes previous selected text in backup");
        check(!new File(file + ".new").exists(), "clear removes temp snapshot");
        actions.pin("a", "日");
        actions.save();
        check(backup.mkdir(), "inject a backup path failure");
        Files.write(new File(backup, "sentinel").toPath(), new byte[] {1});
        expect(IOException.class, new Checked() {
            @Override public void run() throws Exception { actions.clear(); }
        }, "failed erase is surfaced");
        equal(0, actions.entryCount(), "failed clear leaves active choices empty");
        check(actions.hasUnsavedChanges(), "failed clear remains dirty for retry");
        equal("日", QuickLocalActions.load(file).pinned("a"), "failed erase must not claim disk data is removed");
        deleteTree(backup);
        actions.save();
        equal(0, QuickLocalActions.load(file).entryCount(), "save retries failed empty snapshot");
        check(!actions.hasUnsavedChanges(), "successful retry clears dirty flag");
        actions.pin("a", "日");
        File temp = new File(file + ".new");
        check(temp.mkdir(), "inject a temp path failure");
        expect(IOException.class, new Checked() {
            @Override public void run() throws Exception { actions.save(); }
        }, "failed save is surfaced");
        check(actions.hasUnsavedChanges(), "failed save keeps pending choices");
        equal(0, QuickLocalActions.load(file).entryCount(), "failed save preserves committed empty profile");
    }

    private static void orphanTemp(File file) throws Exception {
        Files.write(new File(file + ".new").toPath(), encoded(1, 1, "a", "日"));
        QuickLocalActions loaded = QuickLocalActions.load(file);
        equal(0, loaded.entryCount(), "orphan uncommitted temp does not resurrect actions");
        loaded.clear();
        equal(0, QuickLocalActions.load(file).entryCount(), "clear replaces orphan temp with empty committed state");
        check(!new File(file + ".new").exists(), "orphan temp is removed on clear");
    }

    private static byte[] encoded(int declaredCount, Object... entries) throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(payloadBytes);
        payload.writeInt(declaredCount);
        for (int i = 0; i < entries.length; i += 3) {
            payload.writeByte(((Integer) entries[i]).intValue());
            payload.writeUTF((String) entries[i + 1]);
            payload.writeUTF((String) entries[i + 2]);
        }
        payload.flush();
        byte[] data = payloadBytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(data);
        ByteArrayOutputStream resultBytes = new ByteArrayOutputStream();
        DataOutputStream result = new DataOutputStream(resultBytes);
        result.writeInt(0x51414331);
        result.writeInt(1);
        result.writeInt(data.length);
        result.write(data);
        result.writeLong(crc.getValue());
        result.flush();
        return resultBytes.toByteArray();
    }

    private interface Checked { void run() throws Exception; }

    private static void expect(Class<? extends Throwable> type, Checked action, String message) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) { assertions++; return; }
            throw new AssertionError(message + ": wrong exception", failure);
        }
        throw new AssertionError(message + ": missing exception");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    private static void deleteTree(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove test fixture " + file);
    }
}
