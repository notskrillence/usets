package io.gyrolab.app;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;

/** Keeps the HID app foreground while the system pairing/confirmation UI takes focus. */
public final class PairingService extends Service {
  static volatile boolean active;
  @Override public void onCreate() {
    super.onCreate();
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("bluetooth", "Bluetooth mouse", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification notification = new Notification.Builder(this, "bluetooth")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("GyroLab X Bluetooth mouse")
        .setContentText("Keeps pairing active. Pointer motion pauses when the app is hidden.")
        .setContentIntent(open).setOngoing(true).build();
    startForeground(41, notification);
    active = true;
  }
  @Override public int onStartCommand(Intent intent, int flags, int id) { return START_NOT_STICKY; }
  @Override public void onDestroy() { active = false; super.onDestroy(); }
  @Override public IBinder onBind(Intent intent) { return null; }
}
