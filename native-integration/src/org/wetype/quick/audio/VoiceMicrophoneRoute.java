package org.wetype.quick.audio;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRouting;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Per-recorder preference for the two explicitly marked WeType voice-input paths.
 * Uses public API 24+ only. Does not start/stop recording or change global audio.
 * An accepted preference is NOT evidence that an OEM routed to that microphone.
 */
public final class VoiceMicrophoneRoute {
    private static final String TAG = "WeTypeVoiceMic";
    private static final WeakHashMap<AudioRecord, Session> SESSIONS = new WeakHashMap<>();

    private VoiceMicrophoneRoute() {}

    /** Called after the original recorder's fallback construction succeeds. */
    public static synchronized void beforeStart(AudioRecord record, AudioManager manager) {
        if (Build.VERSION.SDK_INT < 24 || record == null || manager == null
                || SESSIONS.containsKey(record)) return;
        Session session = null;
        try {
            if (record.getState() != AudioRecord.STATE_INITIALIZED) return;
            AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_ALL);
            boolean bluetooth = false;
            AudioDeviceInfo builtin = null;
            if (devices == null) return;
            for (AudioDeviceInfo device : devices) {
                if (device == null) continue;
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || type == AudioDeviceInfo.TYPE_HEARING_AID) bluetooth = true;
                if (builtin == null && device.isSource()
                        && type == AudioDeviceInfo.TYPE_BUILTIN_MIC) builtin = device;
            }
            if (!bluetooth || builtin == null) return;
            session = new Session(record, record.getPreferredDevice(),
                    new Handler(Looper.getMainLooper()));
            SESSIONS.put(record, session);
            // Remember the previous preference before attempting the change, even
            // if a vendor partially applies it and then returns false or throws.
            session.preferenceAttempted = true;
            if (!record.setPreferredDevice(builtin)) {
                Log.w(TAG, "Built-in microphone preference rejected");
                end(record);
                return;
            }
            session.listenerAttempted = true;
            record.addOnRoutingChangedListener(session, session.handler);
            session.status = "PREFERENCE_ACCEPTED";
        } catch (RuntimeException | LinkageError failure) {
            Log.w(TAG, "Microphone preference unavailable; keeping original voice flow");
            if (session != null) end(record);
        }
    }

    /** The route is only meaningful after the original startRecording call. */
    public static synchronized void afterStart(AudioRecord record) {
        Session session = SESSIONS.get(record);
        if (session == null) return;
        observe(record, session);
        // Allow asynchronous platform routing to settle, without polling forever.
        if (SESSIONS.get(record) == session) {
            try {
                session.handler.removeCallbacks(session);
                session.handler.postDelayed(session, 300L);
            } catch (RuntimeException failure) {
                Log.w(TAG, "Delayed route verification unavailable");
            }
        }
    }

    /** Idempotent on cancellation, start failure, stop, and wrapper release. */
    public static synchronized void end(AudioRecord record) {
        Session session = SESSIONS.remove(record);
        if (session == null) return;
        // Invalidate before any callback/removal/restoration can re-enter us.
        session.active = false;
        try {
            session.handler.removeCallbacks(session);
        } catch (RuntimeException ignored) { }
        if (session.listenerAttempted) {
            try {
                record.removeOnRoutingChangedListener(session);
            } catch (RuntimeException | LinkageError failure) {
                Log.w(TAG, "Route listener cleanup unavailable");
            }
        }
        if (session.preferenceAttempted) {
            try {
                if (!record.setPreferredDevice(session.previous)) {
                    Log.w(TAG, "Previous microphone preference could not be restored");
                }
            } catch (RuntimeException | LinkageError failure) {
                // Release still proceeds in the original wrapper/caller.
                Log.w(TAG, "Previous microphone preference restore unavailable");
            }
        }
    }

    /** Diagnostic state for this live session only; no device identity or audio. */
    public static synchronized String status(AudioRecord record) {
        Session session = SESSIONS.get(record);
        return session == null ? "INACTIVE" : session.status;
    }

    private static void observe(AudioRecord record, Session session) {
        if (!session.active || SESSIONS.get(record) != session) return;
        String state;
        try {
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                state = "NOT_RECORDING";
            } else {
                AudioDeviceInfo routed = record.getRoutedDevice();
                if (routed == null) state = "ROUTE_UNVERIFIED";
                else if (routed.isSource() && routed.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC)
                    state = "BUILTIN_CONFIRMED";
                else state = "ROUTE_MISMATCH";
            }
        } catch (RuntimeException | LinkageError failure) {
            state = "ROUTE_UNVERIFIED";
        }
        if (!state.equals(session.status)) {
            session.status = state;
            // Fixed status only: never log PCM, typing, device names or identifiers.
            Log.i(TAG, state);
        }
    }

    private static final class Session implements AudioRouting.OnRoutingChangedListener, Runnable {
        final WeakReference<AudioRecord> record;
        final AudioDeviceInfo previous;
        final Handler handler;
        boolean active = true;
        boolean preferenceAttempted;
        boolean listenerAttempted;
        String status = "PREPARING";

        Session(AudioRecord record, AudioDeviceInfo previous, Handler handler) {
            // No strong record reference in a WeakHashMap value or pending callback.
            this.record = new WeakReference<>(record);
            this.previous = previous;
            this.handler = handler;
        }

        @Override public void onRoutingChanged(AudioRouting router) {
            synchronized (VoiceMicrophoneRoute.class) {
                AudioRecord current = record.get();
                if (current != null && router == current) observe(current, this);
            }
        }

        @Override public void run() {
            synchronized (VoiceMicrophoneRoute.class) {
                AudioRecord current = record.get();
                if (current != null) observe(current, this);
            }
        }
    }
}
