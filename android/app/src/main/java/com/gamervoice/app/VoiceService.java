package com.gamervoice.app; // Updated to match your folder!

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class VoiceService extends Service {
    public static final String ACTION_TOGGLE_MUTE = "ACTION_TOGGLE_MUTE";
    public static final String ACTION_DISCONNECT = "ACTION_DISCONNECT";
    public static final String ACTION_UPDATE_MUTE = "ACTION_UPDATE_MUTE";
    
    private boolean isMuted = false;
    private String roomCode = "Gaming";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (action.equals(ACTION_TOGGLE_MUTE)) {
                isMuted = !isMuted;
                sendBroadcast(new Intent("voice_event").putExtra("event", "mute_toggle"));
                updateNotification();
            } else if (action.equals(ACTION_DISCONNECT)) {
                sendBroadcast(new Intent("voice_event").putExtra("event", "disconnect"));
                stopSelf();
            } else if (action.equals(ACTION_UPDATE_MUTE)) {
                isMuted = intent.getBooleanExtra("isMuted", false);
                roomCode = intent.getStringExtra("roomCode");
                updateNotification();
            }
        } else {
            if (intent != null) roomCode = intent.getStringExtra("roomCode");
            createNotificationChannel();
            startForeground(101, buildNotification());
        }
        return START_NOT_STICKY;
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(101, buildNotification());
    }

    private Notification buildNotification() {
        Intent muteIntent = new Intent(this, VoiceService.class);
        muteIntent.setAction(ACTION_TOGGLE_MUTE);
        PendingIntent mutePending = PendingIntent.getService(this, 0, muteIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent discIntent = new Intent(this, VoiceService.class);
        discIntent.setAction(ACTION_DISCONNECT);
        PendingIntent discPending = PendingIntent.getService(this, 1, discIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String muteText = isMuted ? "🎙️ UNMUTE MIC" : "🎤 MUTE MIC";

        return new NotificationCompat.Builder(this, "VoiceChannel")
                .setContentTitle("NexaVoice - Room: " + roomCode)
                .setContentText(isMuted ? "Your microphone is Muted" : "Your microphone is Active")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Default system mic icon
                .addAction(android.R.drawable.ic_lock_silent_mode_off, muteText, mutePending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DISCONNECT", discPending)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("VoiceChannel", "Voice Chat", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}