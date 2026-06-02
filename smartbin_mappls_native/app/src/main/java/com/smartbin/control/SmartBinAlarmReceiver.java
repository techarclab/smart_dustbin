package com.smartbin.control;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class SmartBinAlarmReceiver extends BroadcastReceiver {
   private static final String ACTION_CHECK_ALERTS = "com.smartbin.control.CHECK_ALERTS";
   private static final int REQUEST_CODE = 7305;
   private static final long FIRST_CHECK_MS = 60000L;
   private static final long REPEAT_CHECK_MS = 900000L;

   public void onReceive(Context var1, Intent var2) {
      final PendingResult var3 = this.goAsync();
      final Context var4 = var1.getApplicationContext();
      (new Thread(new Runnable() {
         public void run() {
            try {
               SmartBinAlertEngine.checkAndNotify(var4);
            } catch (Exception var2) {
            } finally {
               SmartBinAlarmReceiver.schedule(var4, SmartBinAlarmReceiver.REPEAT_CHECK_MS);
               var3.finish();
            }
         }
      })).start();
   }

   public static void schedule(Context var0) {
      schedule(var0, FIRST_CHECK_MS);
   }

   private static void schedule(Context var0, long var1) {
      AlarmManager var3 = (AlarmManager)var0.getSystemService(Context.ALARM_SERVICE);
      if (var3 == null) {
         return;
      }

      PendingIntent var4 = PendingIntent.getBroadcast(var0, REQUEST_CODE, buildIntent(var0), pendingFlags());
      long var5 = System.currentTimeMillis() + var1;
      try {
         if (Build.VERSION.SDK_INT >= 23) {
            var3.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, var5, var4);
         } else {
            var3.set(AlarmManager.RTC_WAKEUP, var5, var4);
         }
      } catch (SecurityException var7) {
         var3.set(AlarmManager.RTC_WAKEUP, var5, var4);
      }
   }

   private static Intent buildIntent(Context var0) {
      Intent var1 = new Intent(var0, SmartBinAlarmReceiver.class);
      var1.setAction(ACTION_CHECK_ALERTS);
      return var1;
   }

   private static int pendingFlags() {
      return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
   }
}
