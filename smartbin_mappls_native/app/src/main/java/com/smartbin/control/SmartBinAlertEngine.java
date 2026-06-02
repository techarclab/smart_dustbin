package com.smartbin.control;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

final class SmartBinAlertEngine {
   private static final String API_BASE = "https://aoacx6u7g2.execute-api.eu-north-1.amazonaws.com";
   private static final String CHANNEL_ID = "smartbin_alerts";

   private SmartBinAlertEngine() {
   }

   static boolean checkAndNotify(Context var0) throws Exception {
      JSONObject var1 = new JSONObject(get(API_BASE + "/bins"));
      JSONArray var2 = var1.optJSONArray("bins");
      JSONObject var3 = new JSONObject(get(API_BASE + "/telemetry"));
      if (var3.has("body")) {
         Object var4 = var3.get("body");
         var3 = var4 instanceof String ? new JSONObject((String)var4) : (JSONObject)var4;
      }

      if (var2 == null) {
         return false;
      }

      boolean var5 = false;
      for(int var6 = 0; var6 < var2.length(); ++var6) {
         JSONObject var7 = var2.optJSONObject(var6);
         if (var7 != null) {
            String var8 = var7.optString("bin_id", "BIN-" + (var6 + 1));
            JSONObject var9 = var3.optJSONObject(var8);
            if (var9 == null) var9 = findInArray(var3.optJSONArray("items"), var8);
            if (var9 == null) var9 = findInArray(var3.optJSONArray("telemetry"), var8);

            int var10 = var9 == null ? 0 : (int)Math.round(var9.has("fill_percent") ? var9.optDouble("fill_percent", 0) : (var9.has("fill") ? var9.optDouble("fill", 0) : var9.optDouble("level", 0)));
            long var11 = var9 == null ? 0L : var9.optLong("updated_at", 0L);
            long var13 = System.currentTimeMillis() / 1000L;
            boolean var15 = var9 != null && (var11 <= 0L || var13 - var11 <= 300L);
            if (var10 >= 80 || !var15) {
               String var16 = cleanPlace(var7.optString("place", var8));
               String var17 = var10 >= 80 ? "full" : "offline";
               String var18 = var8 + ":" + var17;
               if (shouldNotify(var0, var18)) {
                  if (var10 >= 80) {
                     notify(var0, var16 + " bin is full", var8 + " needs collection. Fill level: " + var10 + "%", var18.hashCode());
                  } else {
                     notify(var0, var16 + " bin is offline", var8 + " is not sending recent live data.", var18.hashCode());
                  }
                  var5 = true;
               }
            }
         }
      }

      return var5;
   }

   private static JSONObject findInArray(JSONArray var0, String var1) {
      if (var0 == null) return null;
      for(int var2 = 0; var2 < var0.length(); ++var2) {
         JSONObject var3 = var0.optJSONObject(var2);
         if (var3 != null && var1.equals(var3.optString("bin_id", var3.optString("id", "")))) {
            return var3;
         }
      }

      return null;
   }

   private static boolean shouldNotify(Context var0, String var1) {
      SharedPreferences var2 = var0.getSharedPreferences("smartbin_background_alerts", 0);
      long var3 = System.currentTimeMillis();
      long var5 = var2.getLong(var1, 0L);
      if (var3 - var5 < 30L * 60L * 1000L) {
         return false;
      }

      var2.edit().putLong(var1, var3).apply();
      return true;
   }

   private static void notify(Context var0, String var1, String var2, int var3) {
      if (Build.VERSION.SDK_INT >= 33 && var0.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
         return;
      }

      NotificationManager var4 = (NotificationManager)var0.getSystemService(Context.NOTIFICATION_SERVICE);
      if (var4 == null) {
         return;
      }

      if (Build.VERSION.SDK_INT >= 26) {
         NotificationChannel var5 = new NotificationChannel(CHANNEL_ID, "SmartBin Alerts", NotificationManager.IMPORTANCE_HIGH);
         var5.setDescription("Full bin and device alert notifications");
         var4.createNotificationChannel(var5);
      }

      Intent var6 = new Intent(var0, MainActivity.class);
      var6.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      int var7 = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
      PendingIntent var8 = PendingIntent.getActivity(var0, Math.abs(var3), var6, var7);
      Notification.Builder var9 = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(var0, CHANNEL_ID) : new Notification.Builder(var0);
      var9.setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(var1)
            .setContentText(var2)
            .setContentIntent(var8)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL);
      if (Build.VERSION.SDK_INT >= 21) var9.setColor(Color.rgb(5, 150, 105));
      var4.notify(Math.abs(var3), var9.build());
   }

   private static String cleanPlace(String var0) {
      if (var0 == null || var0.trim().length() == 0) return "SmartBin";
      return var0.replace('_', ' ').trim();
   }

   private static String get(String var0) throws Exception {
      HttpURLConnection var1 = (HttpURLConnection)(new URL(var0)).openConnection();
      var1.setConnectTimeout(10000);
      var1.setReadTimeout(10000);
      var1.setRequestMethod("GET");
      int var2 = var1.getResponseCode();
      BufferedReader var3 = new BufferedReader(new InputStreamReader(var2 >= 200 && var2 < 300 ? var1.getInputStream() : var1.getErrorStream()));
      StringBuilder var4 = new StringBuilder();
      String var5;
      while((var5 = var3.readLine()) != null) {
         var4.append(var5);
      }

      var3.close();
      if (var2 < 200 || var2 >= 300) throw new Exception("HTTP " + var2);
      return var4.toString();
   }
}
