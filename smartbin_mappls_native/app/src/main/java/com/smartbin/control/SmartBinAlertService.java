package com.smartbin.control;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class SmartBinAlertService extends Service {
   private static final String CHANNEL_ID = "smartbin_service";
   private static final int NOTIFICATION_ID = 4102;
   private static final long CHECK_INTERVAL_MS = 60000L;
   private final Handler handler = new Handler(Looper.getMainLooper());
   private final Runnable checker = new Runnable() {
      public void run() {
         (new Thread(new Runnable() {
            public void run() {
               try {
                  SmartBinAlertEngine.checkAndNotify(SmartBinAlertService.this.getApplicationContext());
               } catch (Exception var1) {
               }
            }
         })).start();
         SmartBinAlertService.this.handler.postDelayed(this, CHECK_INTERVAL_MS);
      }
   };

   public void onCreate() {
      super.onCreate();
      this.createServiceChannel();
      this.startForeground(NOTIFICATION_ID, this.buildServiceNotification());
      this.handler.post(this.checker);
   }

   public int onStartCommand(Intent var1, int var2, int var3) {
      this.handler.removeCallbacks(this.checker);
      this.handler.post(this.checker);
      return START_STICKY;
   }

   public void onDestroy() {
      this.handler.removeCallbacks(this.checker);
      super.onDestroy();
   }

   public IBinder onBind(Intent var1) {
      return null;
   }

   private void createServiceChannel() {
      if (Build.VERSION.SDK_INT >= 26) {
         NotificationChannel var1 = new NotificationChannel(CHANNEL_ID, "SmartBin Live Monitor", NotificationManager.IMPORTANCE_LOW);
         var1.setDescription("Keeps SmartBin alerts active when the app is closed");
         NotificationManager var2 = (NotificationManager)this.getSystemService(NOTIFICATION_SERVICE);
         if (var2 != null) {
            var2.createNotificationChannel(var1);
         }
      }
   }

   private Notification buildServiceNotification() {
      Notification.Builder var1 = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
      var1.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SmartBin live alerts active")
            .setContentText("Monitoring bin fill and offline alerts")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(Notification.PRIORITY_LOW);
      if (Build.VERSION.SDK_INT >= 21) {
         var1.setColor(Color.rgb(5, 150, 105));
      }

      return var1.build();
   }
}
