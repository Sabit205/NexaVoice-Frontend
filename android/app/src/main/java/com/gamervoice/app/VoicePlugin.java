package com.gamervoice.app; // Updated to match your folder!

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "VoiceService")
public class VoicePlugin extends Plugin {
    
    private BroadcastReceiver receiver;

    @Override
    public void load() {
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String event = intent.getStringExtra("event");
                if ("mute_toggle".equals(event)) notifyListeners("onMuteToggle", new JSObject());
                if ("disconnect".equals(event)) notifyListeners("onDisconnect", new JSObject());
            }
        };
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(receiver, new IntentFilter("voice_event"), Context.RECEIVER_EXPORTED);
        } else {
            getContext().registerReceiver(receiver, new IntentFilter("voice_event"));
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        String room = call.getString("roomCode", "Unknown");
        Intent serviceIntent = new Intent(getContext(), VoiceService.class);
        serviceIntent.putExtra("roomCode", room);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(serviceIntent);
        } else {
            getContext().startService(serviceIntent);
        }
        call.resolve();
    }

    @PluginMethod
    public void updateMuteStatus(PluginCall call) {
        boolean isMuted = call.getBoolean("isMuted", false);
        String room = call.getString("roomCode", "Unknown");
        Intent serviceIntent = new Intent(getContext(), VoiceService.class);
        serviceIntent.setAction(VoiceService.ACTION_UPDATE_MUTE);
        serviceIntent.putExtra("isMuted", isMuted);
        serviceIntent.putExtra("roomCode", room);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(serviceIntent);
        } else {
            getContext().startService(serviceIntent);
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent serviceIntent = new Intent(getContext(), VoiceService.class);
        getContext().stopService(serviceIntent);
        call.resolve();
    }
}