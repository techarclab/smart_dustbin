package com.smartbin.control;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.GradientDrawable.Orientation;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.mappls.sdk.maps.MapView;
import com.mappls.sdk.maps.Mappls;
import com.mappls.sdk.maps.MapplsMap;
import com.mappls.sdk.maps.MapplsMapOptions;
import com.mappls.sdk.maps.OnMapReadyCallback;
import com.mappls.sdk.maps.camera.CameraPosition;
import com.mappls.sdk.maps.geometry.LatLng;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
   private static final String API_BASE = "https://aoacx6u7g2.execute-api.eu-north-1.amazonaws.com";
   private static final int GREEN = Color.rgb(5, 150, 105);
   private static final int GREEN_DARK = Color.rgb(6, 95, 70);
   private static final int GREEN_SOFT = Color.rgb(220, 252, 231);
   private static final int DARK = Color.rgb(15, 23, 42);
   private static final int MUTED = Color.rgb(100, 116, 139);
   private static final int BG = Color.rgb(239, 247, 242);
   private static final int PANEL = Color.rgb(255, 255, 255);
   private static final int LINE = Color.rgb(226, 232, 240);
   private static final int RED = Color.rgb(225, 29, 72);
   private static final int RED_SOFT = Color.rgb(255, 241, 242);
   private static final int AMBER = Color.rgb(217, 119, 6);
   private static final int AMBER_SOFT = Color.rgb(255, 251, 235);
   private static final int SLATE = Color.rgb(71, 85, 105);
   private final Handler handler = new Handler(Looper.getMainLooper());
   private final ExecutorService executor = Executors.newSingleThreadExecutor();
   private LinearLayout content;
   private MapView activeMapView;
   private MapplsMap activeMapplsMap;
   private FrameLayout activeMarkerLayer;
   private boolean mapFullView = false;
   private boolean loading = false;
   private List currentBins = new ArrayList();
   private final List alertHistory = new ArrayList();
   private boolean currentDemo = false;
   private String currentError = null;
   private int page = 0;
   private String selectedBinId = null;
   private boolean darkTheme = false;
   private boolean authenticated = false;
   private String userName = "Admin";
   private final Runnable poller = new Runnable() {
      public void run() {
         MainActivity.this.loadData();
         MainActivity.this.handler.postDelayed(this, 3000L);
      }
   };

   protected void onCreate(Bundle var1) {
      super.onCreate(var1);
      this.darkTheme = this.getSharedPreferences("smartbin_theme", 0).getBoolean("dark", false);
      SharedPreferences varAuth = this.getSharedPreferences("smartbin_user", 0);
      this.authenticated = varAuth.getBoolean("logged_in", false);
      this.userName = varAuth.getString("name", "Admin");
      Mappls.getInstance(this);
      Window var2 = this.getWindow();
      var2.setStatusBarColor(this.darkTheme ? Color.rgb(2, 44, 34) : GREEN_DARK);
      var2.setNavigationBarColor(this.bgColor(PANEL));
      this.loadAlertHistory();
      this.createNotificationChannel();
      this.requestNotificationPermission();
      this.scheduleBackgroundAlerts();
      this.setContentView(new SplashView());
      this.handler.postDelayed(new Runnable() {
         public void run() {
            if (MainActivity.this.authenticated) {
               MainActivity.this.startDashboard();
            } else {
               MainActivity.this.renderAuthPage(false);
            }
         }
      }, 2100L);
   }

   private void startDashboard() {
      this.createMainContent();
      this.renderLoading();
      this.poller.run();
   }

   private void createMainContent() {
      ScrollView var3 = new ScrollView(this);
      var3.setFillViewport(true);
      var3.setBackgroundColor(this.bgColor(BG));
      this.content = this.col();
      this.content.setPadding(this.dp(16), this.dp(32), this.dp(16), this.dp(24));
      var3.addView(this.content);
      this.setContentView(var3);
   }

   private void renderAuthPage(final boolean var1) {
      this.handler.removeCallbacks(this.poller);
      ScrollView var2 = new ScrollView(this);
      var2.setFillViewport(true);
      var2.setBackgroundColor(this.bgColor(BG));
      LinearLayout var3 = this.col();
      var3.setGravity(17);
      var3.setPadding(this.dp(18), this.dp(24), this.dp(18), this.dp(24));
      var2.addView(var3, new ScrollView.LayoutParams(-1, -1));
      LinearLayout var4 = this.col();
      var4.setGravity(1);
      var4.setPadding(this.dp(18), this.dp(22), this.dp(18), this.dp(22));
      var4.setBackground(this.gradient(GREEN_DARK, GREEN));
      var4.setElevation((float)this.dp(8));
      var3.addView(var4, new LinearLayout.LayoutParams(-1, -2));
      TextView var5 = this.text("SmartBin", 34, -1, true);
      var5.setGravity(17);
      var4.addView(var5);
      TextView var6 = this.text("Control access for live city operations", 13, Color.rgb(220, 252, 231), false);
      var6.setGravity(17);
      var6.setPadding(0, this.dp(8), 0, 0);
      var4.addView(var6);
      LinearLayout var7 = this.panel();
      LinearLayout.LayoutParams var8 = new LinearLayout.LayoutParams(-1, -2);
      var8.setMargins(0, this.dp(18), 0, 0);
      var7.setLayoutParams(var8);
      var7.addView(this.text(var1 ? "Create Account" : "Welcome Back", 25, DARK, true));
      TextView var9 = this.text(var1 ? "Register operator details and continue to SmartBin dashboard." : "Login with your registered phone number.", 13, MUTED, false);
      var9.setPadding(0, this.dp(6), 0, this.dp(14));
      var7.addView(var9);
      final EditText varName = this.input("Name", false);
      final EditText varPhone = this.input("Phone number", true);
      final EditText varWard = this.input("Ward", false);
      final EditText varRoute = this.input("Route", false);
      final EditText varArea = this.input("Area", false);
      if (var1) {
         var7.addView(varName);
      }
      var7.addView(varPhone);
      if (var1) {
         var7.addView(varWard);
         var7.addView(varRoute);
         var7.addView(varArea);
      }

      TextView var10 = this.text(var1 ? "Register & Continue" : "Login", 15, -1, true);
      var10.setGravity(17);
      var10.setPadding(this.dp(14), this.dp(13), this.dp(14), this.dp(13));
      var10.setBackground(this.round(GREEN, 18));
      LinearLayout.LayoutParams var11 = new LinearLayout.LayoutParams(-1, -2);
      var11.setMargins(0, this.dp(10), 0, 0);
      var7.addView(var10, var11);
      var10.setOnClickListener(new View.OnClickListener() {
         public void onClick(View var2) {
            if (var1) {
               MainActivity.this.registerUser(varName.getText().toString(), varPhone.getText().toString(), varWard.getText().toString(), varRoute.getText().toString(), varArea.getText().toString());
            } else {
               MainActivity.this.loginUser(varPhone.getText().toString());
            }
         }
      });
      TextView var12 = this.text(var1 ? "Already registered? Login" : "New operator? Register", 13, GREEN_DARK, true);
      var12.setGravity(17);
      var12.setPadding(0, this.dp(14), 0, 0);
      var12.setOnClickListener(new View.OnClickListener() {
         public void onClick(View var2) {
            MainActivity.this.renderAuthPage(!var1);
         }
      });
      var7.addView(var12);
      var3.addView(var7);
      this.setContentView(var2);
   }

   private EditText input(String var1, boolean var2) {
      EditText var3 = new EditText(this);
      var3.setHint(var1);
      var3.setTextSize(15.0F);
      var3.setSingleLine(true);
      var3.setInputType(var2 ? InputType.TYPE_CLASS_PHONE : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
      var3.setTextColor(this.textColor(DARK));
      var3.setHintTextColor(this.textColor(MUTED));
      var3.setPadding(this.dp(14), this.dp(10), this.dp(14), this.dp(10));
      var3.setBackground(this.round(PANEL, 16, LINE));
      LinearLayout.LayoutParams var4 = new LinearLayout.LayoutParams(-1, -2);
      var4.setMargins(0, 0, 0, this.dp(10));
      var3.setLayoutParams(var4);
      return var3;
   }

   protected void onDestroy() {
      this.handler.removeCallbacksAndMessages((Object)null);
      this.executor.shutdownNow();
      this.destroyActiveMap();
      super.onDestroy();
   }

   protected void onStart() {
      super.onStart();
      if (this.activeMapView != null) {
         this.activeMapView.onStart();
      }
   }

   protected void onResume() {
      super.onResume();
      if (this.activeMapView != null) {
         this.activeMapView.onResume();
      }
   }

   protected void onPause() {
      if (this.activeMapView != null) {
         this.activeMapView.onPause();
      }

      super.onPause();
   }

   protected void onStop() {
      if (this.activeMapView != null) {
         this.activeMapView.onStop();
      }

      super.onStop();
   }

   public void onLowMemory() {
      super.onLowMemory();
      if (this.activeMapView != null) {
         this.activeMapView.onLowMemory();
      }
   }

   public void onBackPressed() {
      if (this.selectedBinId != null) {
         this.selectedBinId = null;
         this.renderCurrent();
      } else if (this.page != 0) {
         this.page = 0;
         this.renderCurrent();
      } else {
         super.onBackPressed();
      }
   }

   private void renderLoading() {
      this.content.removeAllViews();
      this.content.addView(this.appHeader("Connecting to live data"));
      LinearLayout var1 = this.heroPanel("Smart city waste intelligence", "Loading live bin telemetry, routes, alerts and operational health.");
      ProgressBar var2 = new ProgressBar(this);
      var1.addView(var2, new LinearLayout.LayoutParams(-1, this.dp(44)));
      this.content.addView(var1);
   }

   private void loadData() {
      if (!this.loading) {
         this.loading = true;
         this.executor.execute(new Runnable() {
            public void run() {
               try {
                  JSONArray var1 = (new JSONObject(MainActivity.this.get("https://aoacx6u7g2.execute-api.eu-north-1.amazonaws.com/bins"))).optJSONArray("bins");
                  JSONObject var2 = new JSONObject(MainActivity.this.get("https://aoacx6u7g2.execute-api.eu-north-1.amazonaws.com/telemetry"));
                  if (var2.has("body")) {
                     Object var3 = var2.get("body");
                     var2 = var3 instanceof String ? new JSONObject((String)var3) : (JSONObject)var3;
                  }

                  final List var9 = MainActivity.this.merge(var1, var2);
                  MainActivity.this.handler.post(new Runnable() {
                     public void run() {
                        MainActivity.this.render(var9, false, (String)null);
                     }
                  });
               } catch (final Exception var7) {
                  MainActivity.this.handler.post(new Runnable() {
                     public void run() {
                        if (MainActivity.this.currentBins.size() > 0) {
                           MainActivity.this.currentError = var7.getMessage();
                           MainActivity.this.currentDemo = false;
                           if (MainActivity.this.page != 2) {
                              MainActivity.this.renderCurrent();
                           }
                        } else {
                           MainActivity.this.render(MainActivity.this.demoBins(), true, var7.getMessage());
                        }
                     }
                  });
               } finally {
                  MainActivity.this.loading = false;
               }

            }
         });
      }
   }

   private String get(String var1) throws Exception {
      HttpURLConnection var2 = (HttpURLConnection)(new URL(var1)).openConnection();
      var2.setConnectTimeout(7000);
      var2.setReadTimeout(7000);
      var2.setRequestMethod("GET");
      int var3 = var2.getResponseCode();
      BufferedReader var4 = new BufferedReader(new InputStreamReader(var3 >= 200 && var3 < 300 ? var2.getInputStream() : var2.getErrorStream()));
      StringBuilder var5 = new StringBuilder();

      String var6;
      while((var6 = var4.readLine()) != null) {
         var5.append(var6);
      }

      var4.close();
      if (var3 >= 200 && var3 < 300) {
         return var5.toString();
      } else {
         throw new Exception("API returned HTTP " + var3);
      }
   }

   private String post(String var1, JSONObject var2) throws Exception {
      HttpURLConnection var3 = (HttpURLConnection)(new URL(var1)).openConnection();
      var3.setConnectTimeout(7000);
      var3.setReadTimeout(7000);
      var3.setRequestMethod("POST");
      var3.setRequestProperty("Content-Type", "application/json");
      var3.setDoOutput(true);
      OutputStream var4 = var3.getOutputStream();
      var4.write(var2.toString().getBytes("UTF-8"));
      var4.close();
      int var5 = var3.getResponseCode();
      BufferedReader var6 = new BufferedReader(new InputStreamReader(var5 >= 200 && var5 < 300 ? var3.getInputStream() : var3.getErrorStream()));
      StringBuilder var7 = new StringBuilder();
      String var8;
      while((var8 = var6.readLine()) != null) {
         var7.append(var8);
      }

      var6.close();
      if (var5 >= 200 && var5 < 300) {
         return var7.toString();
      } else {
         throw new Exception("API returned HTTP " + var5);
      }
   }

   private void loginUser(String var1) {
      String var2 = this.cleanInput(var1);
      SharedPreferences var3 = this.getSharedPreferences("smartbin_user", 0);
      String var4 = var3.getString("phone", "");
      if (var2.length() < 6) {
         Toast.makeText(this, "Enter registered phone number", 0).show();
      } else if (var4.length() > 0 && !var2.equals(var4)) {
         Toast.makeText(this, "Phone not registered on this device. Please register.", 0).show();
      } else {
         this.authenticated = true;
         this.userName = var3.getString("name", "Admin");
         var3.edit().putBoolean("logged_in", true).putString("phone", var2).apply();
         Toast.makeText(this, "Welcome to SmartBin", 0).show();
         this.startDashboard();
      }
   }

   private void registerUser(String var1, String var2, String var3, String var4, String var5) {
      final String varName = this.cleanInput(var1);
      final String varPhone = this.cleanInput(var2);
      final String varWard = this.cleanInput(var3);
      final String varRoute = this.cleanInput(var4);
      final String varArea = this.cleanInput(var5);
      if (varName.length() < 2 || varPhone.length() < 6 || varWard.length() == 0 || varRoute.length() == 0 || varArea.length() == 0) {
         Toast.makeText(this, "Please fill all registration details", 0).show();
         return;
      }

      this.getSharedPreferences("smartbin_user", 0).edit().putBoolean("logged_in", true).putString("name", varName).putString("phone", varPhone).putString("ward", varWard).putString("route", varRoute).putString("area", varArea).apply();
      this.authenticated = true;
      this.userName = varName;
      Toast.makeText(this, "Registration saved", 0).show();
      this.executor.execute(new Runnable() {
         public void run() {
            try {
               JSONObject var1 = new JSONObject();
               var1.put("name", varName);
               var1.put("phone", varPhone);
               var1.put("ward", varWard);
               var1.put("route", varRoute);
               var1.put("area", varArea);
               var1.put("registered_at", System.currentTimeMillis() / 1000L);
               var1.put("app", "SmartBin Control");
               try {
                  MainActivity.this.post(API_BASE + "/users", var1);
               } catch (Exception var3) {
                  MainActivity.this.post(API_BASE + "/register", var1);
               }
            } catch (Exception var4) {
            }
         }
      });
      this.startDashboard();
   }

   private String cleanInput(String var1) {
      return var1 == null ? "" : var1.trim();
   }

   private List merge(JSONArray var1, JSONObject var2) {
      ArrayList var3 = new ArrayList();
      if (var1 == null) {
         return var3;
      } else {
         long var4 = System.currentTimeMillis() / 1000L;

         for(int var6 = 0; var6 < var1.length(); ++var6) {
            JSONObject var7 = var1.optJSONObject(var6);
            if (var7 != null) {
               String var8 = var7.optString("bin_id", "BIN-" + (var6 + 1));
               JSONObject var9 = var2.optJSONObject(var8);
               if (var9 == null) {
                  JSONArray var9a = var2.optJSONArray("items");
                  if (var9a == null) {
                     var9a = var2.optJSONArray("telemetry");
                  }

                  if (var9a != null) {
                     for(int var9b = 0; var9b < var9a.length(); ++var9b) {
                        JSONObject var9c = var9a.optJSONObject(var9b);
                        if (var9c != null && var8.equals(var9c.optString("bin_id", var9c.optString("id", "")))) {
                           var9 = var9c;
                           break;
                        }
                     }
                  }
               }

               int var10 = var9 == null ? 0 : (int)Math.round(var9.has("fill_percent") ? var9.optDouble("fill_percent", (double)0.0F) : (var9.has("fill") ? var9.optDouble("fill", (double)0.0F) : var9.optDouble("level", (double)0.0F)));
               long var11 = var9 == null ? 0L : var9.optLong("updated_at", var4);
               boolean var13 = var9 != null && (var11 <= 0L || var4 - var11 <= 300L);
               String var14 = var7.optString("place", "Unknown");
               double var15 = var7.has("latitude") ? var7.optDouble("latitude") : (var7.has("lat") ? var7.optDouble("lat") : MainActivity.this.defaultLat(var14));
               double var17 = var7.has("longitude") ? var7.optDouble("longitude") : (var7.has("lng") ? var7.optDouble("lng") : (var7.has("lon") ? var7.optDouble("lon") : MainActivity.this.defaultLng(var14)));
               var3.add(new Bin(var8, var7.optString("bin_name", var8), var14, var7.optString("ward", "-"), var7.optString("route", "-"), var10, var9 == null ? -1 : var9.optInt("battery", -1), var9 == null ? -999 : var9.optInt("rssi", -999), var13, var15, var17));
            }
         }

         return var3;
      }
   }

   private void render(List var1, boolean var2, String var3) {
      this.currentBins = var1;
      this.currentDemo = var2;
      this.currentError = var3;
      this.captureAlertHistory(var1);
      if (this.page == 2 && this.activeMapView != null && this.activeMarkerLayer != null) {
         this.refreshMapMarkers();
         return;
      }

      this.renderCurrent();
   }

   private void renderCurrent() {
      this.destroyActiveMap();
      if (this.selectedBinId != null) {
         Bin var1 = this.findBin(this.selectedBinId);
         if (var1 != null) {
            this.renderBinDetails(var1);
            return;
         }

         this.selectedBinId = null;
      }

      if (this.page == 1) {
         this.renderBinsPage();
      } else if (this.page == 2) {
         this.renderMapPage();
      } else if (this.page == 3) {
         this.renderAlertsPage();
      } else {
         this.renderDashboard();
      }

   }

   private void renderDashboard() {
      List var1 = this.currentBins;
      boolean var2 = this.currentDemo;
      String var3 = this.currentError;
      this.content.removeAllViews();
      Stats var4 = this.stats(var1);
      String var5 = (var2 ? "Demo mode" : "Live sync") + " | " + DateFormat.getTimeInstance(3).format(new Date());
      this.content.addView(this.appHeader(var5));
      this.content.addView(this.navBar());
      LinearLayout var6 = this.heroPanel("SmartBin Command Center", "A live operations view for cleaner streets, faster response and data-backed waste collection.");
      var6.addView(this.heroMetrics(var4, var2));
      this.content.addView(var6);
      if (var2 && var3 != null) {
         this.content.addView(this.banner("API is not reachable right now, so the app is showing realistic demo data.", AMBER, AMBER_SOFT));
      }

      this.content.addView(this.kpiGrid(var4));
      this.content.addView(this.section("Priority Response"));
      boolean var7 = false;

      for(int var8 = 0; var8 < var1.size(); ++var8) {
         Bin var9 = (Bin)var1.get(var8);
         if (var9.fill >= 80 || !var9.online) {
            var7 = true;
            this.content.addView(this.alertCard(var9));
         }
      }

      if (!var7) {
         this.content.addView(this.banner("No critical bins right now. Fleet is healthy and under control.", GREEN, GREEN_SOFT));
      }

      this.content.addView(this.section("City Operations Snapshot"));
      this.content.addView(this.operationsCard(var4));
      this.content.addView(this.section("Live Bin Intelligence"));

      for(int var10 = 0; var10 < var1.size(); ++var10) {
         this.content.addView(this.binCard((Bin)var1.get(var10)));
      }

   }

   private void renderBinsPage() {
      this.content.removeAllViews();
      Stats var1 = this.stats(this.currentBins);
      this.content.addView(this.appHeader((this.currentDemo ? "Demo mode" : "Live sync") + " | " + DateFormat.getTimeInstance(3).format(new Date())));
      this.content.addView(this.navBar());
      LinearLayout var2 = this.heroPanel("Bins Registry", "Tap any bin to inspect live fill status, device health, location and route details.");
      var2.addView(this.heroMetrics(var1, this.currentDemo));
      this.content.addView(var2);
      if (this.currentDemo && this.currentError != null) {
         this.content.addView(this.banner("Showing demo data until the live API responds.", AMBER, AMBER_SOFT));
      }

      this.content.addView(this.section("All SmartBins"));
      if (this.currentBins.size() == 0) {
         this.content.addView(this.banner("No bins found in the registry yet.", AMBER, AMBER_SOFT));
      } else {
         for(int var3 = 0; var3 < this.currentBins.size(); ++var3) {
            final Bin var4 = (Bin)this.currentBins.get(var3);
            View var5 = this.binCard(var4);
            var5.setOnClickListener(new View.OnClickListener() {
               public void onClick(View var1) {
                  MainActivity.this.selectedBinId = var4.id;
                  MainActivity.this.renderCurrent();
               }
            });
            this.content.addView(var5);
         }

      }
   }

   private void renderAlertsPage() {
      this.content.removeAllViews();
      Stats var1 = this.stats(this.currentBins);
      this.content.addView(this.appHeader((this.currentDemo ? "Demo mode" : "Live sync") + " | " + DateFormat.getTimeInstance(3).format(new Date())));
      this.content.addView(this.navBar());
      LinearLayout var2 = this.heroPanel("Alerts Center", "Immediate attention queue for full bins, watch-level bins and offline devices.");
      var2.addView(this.heroMetrics(var1, this.currentDemo));
      this.content.addView(var2);
      this.content.addView(this.section("Critical And Offline"));
      boolean var3 = false;

      for(int var4 = 0; var4 < this.currentBins.size(); ++var4) {
         final Bin var5 = (Bin)this.currentBins.get(var4);
         if (var5.fill >= 80 || !var5.online) {
            var3 = true;
            View var6 = this.alertCard(var5);
            var6.setOnClickListener(new View.OnClickListener() {
               public void onClick(View var1) {
                  MainActivity.this.selectedBinId = var5.id;
                  MainActivity.this.renderCurrent();
               }
            });
            this.content.addView(var6);
         }
      }

      if (!var3) {
         this.content.addView(this.banner("No critical alerts. Operations are healthy right now.", GREEN, GREEN_SOFT));
      }

      this.content.addView(this.section("Watch List"));
      boolean var8 = false;

      for(int var9 = 0; var9 < this.currentBins.size(); ++var9) {
         final Bin var10 = (Bin)this.currentBins.get(var9);
         if (var10.online && var10.fill >= 50 && var10.fill < 80) {
            var8 = true;
            View var7 = this.binCard(var10);
            var7.setOnClickListener(new View.OnClickListener() {
               public void onClick(View var1) {
                  MainActivity.this.selectedBinId = var10.id;
                  MainActivity.this.renderCurrent();
               }
            });
            this.content.addView(var7);
         }
      }

      if (!var8) {
         this.content.addView(this.banner("No bins are in watch state.", GREEN, GREEN_SOFT));
      }

      this.content.addView(this.section("Alert History"));
      if (this.alertHistory.size() == 0) {
         this.content.addView(this.banner("No saved alert history yet. Critical and offline events will appear here automatically.", GREEN, GREEN_SOFT));
      } else {
         for(int var11 = 0; var11 < this.alertHistory.size(); ++var11) {
            this.content.addView(this.alertHistoryCard((String)this.alertHistory.get(var11)));
         }
      }

   }

   private void renderMapPage() {
      this.content.removeAllViews();
      Stats var1 = this.stats(this.currentBins);
      this.content.addView(this.appHeader((this.currentDemo ? "Demo mode" : "Live sync") + " | " + DateFormat.getTimeInstance(3).format(new Date())));
      this.content.addView(this.navBar());
      LinearLayout var2 = this.heroPanel("Live City Map", "Real Mappls city view with SmartBin locations, fill status and quick inspection.");
      var2.addView(this.heroMetrics(var1, this.currentDemo));
      this.content.addView(var2);
      if (this.currentDemo && this.currentError != null) {
         this.content.addView(this.banner("Showing demo markers until the live API responds.", AMBER, AMBER_SOFT));
      }

      this.content.addView(this.section("Mappls SmartBin Network"));
      this.content.addView(this.mapplsCard());
      this.content.addView(this.section("Priority On Map"));
      boolean var3 = false;

      for(int var4 = 0; var4 < this.currentBins.size(); ++var4) {
         final Bin var5 = (Bin)this.currentBins.get(var4);
         if (var5.fill >= 80 || !var5.online) {
            var3 = true;
            View var6 = this.alertCard(var5);
            var6.setOnClickListener(new View.OnClickListener() {
               public void onClick(View var1) {
                  MainActivity.this.selectedBinId = var5.id;
                  MainActivity.this.renderCurrent();
               }
            });
            this.content.addView(var6);
         }
      }

      if (!var3) {
         this.content.addView(this.banner("No critical map alerts right now.", GREEN, GREEN_SOFT));
      }
   }

   private View mapplsCard() {
      FrameLayout var1 = new FrameLayout(this);
      var1.setBackground(this.round(PANEL, 22, LINE));
      var1.setPadding(this.dp(1), this.dp(1), this.dp(1), this.dp(1));
      var1.setElevation((float)this.dp(3));
      int var2Height = this.mapFullView ? Math.max(this.dp(560), this.getResources().getDisplayMetrics().heightPixels - this.dp(190)) : this.dp(430);
      LinearLayout.LayoutParams var2 = new LinearLayout.LayoutParams(-1, var2Height);
      var2.setMargins(0, 0, 0, this.dp(14));
      var1.setLayoutParams(var2);
      LatLng var3Center = this.currentBins.size() > 0 ? new LatLng(((Bin)this.currentBins.get(0)).lat, ((Bin)this.currentBins.get(0)).lng) : new LatLng(17.4386, 78.3915);
      MapplsMapOptions var3 = MapplsMapOptions.createFromAttributes(this, (AttributeSet)null).camera((new CameraPosition.Builder()).target(var3Center).zoom(11.0).build());
      this.activeMapView = new MapView(this, var3);
      this.activeMapView.setOnTouchListener(new View.OnTouchListener() {
         public boolean onTouch(View var1, MotionEvent var2) {
            var1.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
         }
      });
      var1.addView(this.activeMapView, new FrameLayout.LayoutParams(-1, -1));
      this.activeMarkerLayer = new FrameLayout(this);
      this.activeMarkerLayer.setClickable(false);
      var1.addView(this.activeMarkerLayer, new FrameLayout.LayoutParams(-1, -1));
      TextView var4 = this.text(this.mapFullView ? "Exit view" : "Full view", 12, -1, true);
      var4.setGravity(17);
      var4.setPadding(this.dp(12), this.dp(8), this.dp(12), this.dp(8));
      var4.setBackground(this.round(GREEN, 999));
      var4.setElevation((float)this.dp(8));
      var4.setOnClickListener(new View.OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.mapFullView = !MainActivity.this.mapFullView;
            MainActivity.this.renderCurrent();
         }
      });
      FrameLayout.LayoutParams var5 = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.TOP);
      var5.setMargins(0, this.dp(12), this.dp(12), 0);
      var1.addView(var4, var5);
      this.activeMapView.onCreate((Bundle)null);
      this.activeMapView.onStart();
      this.activeMapView.onResume();
      this.activeMapView.getMapAsync(new OnMapReadyCallback() {
         public void onMapReady(MapplsMap var1) {
            MainActivity.this.activeMapplsMap = var1;
            MainActivity.this.addMapOverlay(MainActivity.this.activeMarkerLayer);
            MainActivity.this.updateMapMarkers();
            var1.addOnCameraIdleListener(new MapplsMap.OnCameraIdleListener() {
               public void onCameraIdle() {
                  MainActivity.this.updateMapMarkers();
               }
            });
            var1.addOnCameraMoveListener(new MapplsMap.OnCameraMoveListener() {
               public void onCameraMove() {
                  MainActivity.this.updateMapMarkers();
               }
            });
         }

         public void onMapError(int var1, String var2) {
         }
      });
      return var1;
   }

   private void addMapOverlay(final FrameLayout var1) {
      for(int var2 = 0; var2 < this.currentBins.size(); ++var2) {
         this.addMapMarker(var1, (Bin)this.currentBins.get(var2));
      }
   }

   private void refreshMapMarkers() {
      if (this.activeMarkerLayer != null) {
         this.activeMarkerLayer.removeAllViews();
         this.addMapOverlay(this.activeMarkerLayer);
         this.updateMapMarkers();
      }
   }

   private void addMapMarker(final FrameLayout var1, final Bin var2) {
      final MapBinMarker var5 = new MapBinMarker(this.colorFor(var2));
      var5.setElevation((float)this.dp(5));
      var5.setOnClickListener(new View.OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.selectedBinId = var2.id;
            MainActivity.this.renderCurrent();
         }
      });
      var1.addView(var5, new FrameLayout.LayoutParams(this.dp(38), this.dp(38)));
      var5.setTag(var2);
   }

   private void updateMapMarkers() {
      if (this.activeMapplsMap != null && this.activeMarkerLayer != null) {
         for(int var1 = 0; var1 < this.activeMarkerLayer.getChildCount(); ++var1) {
            View var2 = this.activeMarkerLayer.getChildAt(var1);
            Object var3 = var2.getTag();
            if (var3 instanceof Bin) {
               Bin var4 = (Bin)var3;
               PointF var5 = this.activeMapplsMap.getProjection().toScreenLocation(new LatLng(var4.lat, var4.lng));
               var2.setX(var5.x - (float)this.dp(19));
               var2.setY(var5.y - (float)this.dp(19));
               var2.setVisibility(var5.x >= -40.0F && var5.x <= (float)this.activeMarkerLayer.getWidth() + 40.0F && var5.y >= -40.0F && var5.y <= (float)this.activeMarkerLayer.getHeight() + 40.0F ? 0 : 4);
            }
         }
      }
   }

   private void destroyActiveMap() {
      if (this.activeMapView != null) {
         this.activeMapView.onPause();
         this.activeMapView.onStop();
         this.activeMapView.onDestroy();
         this.activeMapView = null;
         this.activeMapplsMap = null;
         this.activeMarkerLayer = null;
      }
   }

   private void renderBinDetails(Bin var1) {
      this.content.removeAllViews();
      LinearLayout var2 = this.row();
      TextView var3 = this.text("Back", 13, GREEN_DARK, true);
      var3.setGravity(17);
      var3.setPadding(this.dp(12), this.dp(8), this.dp(12), this.dp(8));
      var3.setBackground(this.round(GREEN_SOFT, 999));
      var3.setOnClickListener(new View.OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.selectedBinId = null;
            MainActivity.this.renderCurrent();
         }
      });
      var2.addView(var3);
      TextView var4 = this.text("Bin Details", 22, DARK, true);
      var4.setPadding(this.dp(12), 0, 0, 0);
      var2.addView(var4, new LinearLayout.LayoutParams(0, -2, 1.0F));
      var2.addView(this.statusChip(this.status(var1), this.colorFor(var1)));
      var2.setPadding(0, 0, 0, this.dp(14));
      this.content.addView(var2);
      LinearLayout var5 = this.heroPanel(var1.name, var1.id + " | " + var1.place + " | " + var1.route);
      TextView var6 = this.text(var1.fill + "%", 56, -1, true);
      var6.setGravity(17);
      var5.addView(var6);
      TextView var7 = this.text(this.fillMessage(var1), 15, Color.rgb(220, 252, 231), true);
      var7.setGravity(17);
      var7.setPadding(0, 0, 0, this.dp(10));
      var5.addView(var7);
      var5.addView(this.fillBar(var1.fill, -1));
      this.content.addView(var5);
      LinearLayout var8 = this.panel();
      var8.addView(this.text("Collection decision", 15, DARK, true));
      TextView var9 = this.text(this.actionText(var1), 20, this.colorFor(var1), true);
      var9.setPadding(0, this.dp(8), 0, this.dp(4));
      var8.addView(var9);
      var8.addView(this.text("Status is calculated from fill percentage and latest telemetry freshness.", 12, MUTED, false));
      this.content.addView(var8);
      this.content.addView(this.section("Device Health"));
      LinearLayout var10 = this.row();
      var10.addView(this.kpi("Fill", var1.fill + "%", this.fillMessage(var1), this.colorFor(var1)));
      var10.addView(this.kpi("Online", var1.online ? "Yes" : "No", var1.online ? "Live telemetry" : "No recent signal", var1.online ? GREEN : RED));
      this.content.addView(var10);
      LinearLayout var11 = this.row();
      var11.addView(this.kpi("Battery", var1.battery < 0 ? "-" : var1.battery + "%", "Device power", var1.battery < 30 && var1.battery >= 0 ? AMBER : GREEN));
      var11.addView(this.kpi("Signal", var1.rssi == -999 ? "-" : var1.rssi + "", "RSSI dBm", var1.rssi == -999 ? AMBER : GREEN));
      this.content.addView(var11);
      this.content.addView(this.section("Location And Route"));
      LinearLayout var12 = this.panel();
      var12.addView(this.detailLine("Place", var1.place));
      var12.addView(this.detailLine("Ward", var1.ward));
      var12.addView(this.detailLine("Route", var1.route));
      var12.addView(this.detailLine("Bin ID", var1.id));
      TextView var13 = this.text("Go to Google Maps", 15, -1, true);
      var13.setGravity(17);
      var13.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(12));
      var13.setBackground(this.round(GREEN, 16));
      var13.setOnClickListener(new View.OnClickListener() {
         public void onClick(View var2) {
            MainActivity.this.openGoogleMaps(var1);
         }
      });
      LinearLayout.LayoutParams var14 = new LinearLayout.LayoutParams(-1, -2);
      var14.setMargins(0, MainActivity.this.dp(12), 0, 0);
      var12.addView(var13, var14);
      this.content.addView(var12);
   }

   private LinearLayout appHeader(String var1) {
      LinearLayout var2 = this.col();
      var2.setPadding(0, 0, 0, this.dp(14));
      LinearLayout var3 = this.row();
      var3.setGravity(17);
      TextView varLeft = this.text("", 12, MUTED, false);
      LinearLayout varBrand = this.row();
      varBrand.setGravity(17);
      varBrand.addView(this.text("Smart", 22, DARK, true));
      varBrand.addView(this.text("Bin", 22, GREEN, true));
      TextView varSwitch = this.text(this.darkTheme ? "Light" : "Dark", 12, this.darkTheme ? Color.rgb(220, 252, 231) : GREEN_DARK, true);
      varSwitch.setGravity(17);
      varSwitch.setPadding(this.dp(12), this.dp(7), this.dp(12), this.dp(7));
      varSwitch.setBackground(this.round(this.darkTheme ? Color.rgb(6, 78, 59) : GREEN_SOFT, 999, this.darkTheme ? Color.rgb(20, 184, 166) : GREEN));
      varSwitch.setOnClickListener(new View.OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.darkTheme = !MainActivity.this.darkTheme;
            MainActivity.this.getSharedPreferences("smartbin_theme", 0).edit().putBoolean("dark", MainActivity.this.darkTheme).apply();
            MainActivity.this.getWindow().setStatusBarColor(MainActivity.this.darkTheme ? Color.rgb(2, 44, 34) : GREEN_DARK);
            MainActivity.this.getWindow().setNavigationBarColor(MainActivity.this.bgColor(PANEL));
            MainActivity.this.createMainContent();
            MainActivity.this.renderCurrent();
         }
      });
      var3.addView(varLeft, new LinearLayout.LayoutParams(this.dp(58), -2));
      var3.addView(varBrand, new LinearLayout.LayoutParams(0, -2, 1.0F));
      var3.addView(varSwitch, new LinearLayout.LayoutParams(this.dp(58), -2));
      var2.addView(var3, new LinearLayout.LayoutParams(-1, -2));
      TextView var4 = this.text(var1, 11, GREEN_DARK, true);
      var4.setGravity(17);
      var4.setPadding(this.dp(10), this.dp(6), this.dp(10), this.dp(6));
      var4.setBackground(this.round(GREEN_SOFT, 999));
      LinearLayout.LayoutParams var5 = new LinearLayout.LayoutParams(-2, -2);
      var5.gravity = 1;
      var5.setMargins(0, this.dp(8), 0, 0);
      var2.addView(var4, var5);
      return var2;
   }

   private LinearLayout heroPanel(String var1, String var2) {
      LinearLayout var3 = this.col();
      var3.setPadding(this.dp(18), this.dp(18), this.dp(18), this.dp(18));
      var3.setBackground(this.gradient(GREEN_DARK, GREEN));
      var3.setElevation((float)this.dp(4));
      LinearLayout.LayoutParams var4 = new LinearLayout.LayoutParams(-1, -2);
      var4.setMargins(0, 0, 0, this.dp(14));
      var3.setLayoutParams(var4);
      var3.addView(this.text(var1, 26, -1, true));
      TextView var5 = this.text(var2, 13, Color.rgb(220, 252, 231), false);
      var5.setPadding(0, this.dp(6), 0, this.dp(14));
      var3.addView(var5);
      return var3;
   }

   private LinearLayout heroMetrics(Stats var1, boolean var2) {
      LinearLayout var3 = this.row();
      var3.addView(this.heroMetric("Bins", String.valueOf(var1.total)));
      var3.addView(this.heroMetric("Online", var1.online + "/" + var1.total));
      var3.addView(this.heroMetric("Critical", String.valueOf(var1.critical)));
      var3.addView(this.heroMetric("Mode", var2 ? "Demo" : "Live"));
      return var3;
   }

   private View heroMetric(String var1, String var2) {
      LinearLayout var3 = this.col();
      var3.setGravity(17);
      var3.setPadding(this.dp(7), this.dp(8), this.dp(7), this.dp(8));
      var3.setBackground(this.round(Color.argb(36, 255, 255, 255), 14));
      TextView var4 = this.text(var2, 18, -1, true);
      var4.setGravity(17);
      TextView var5 = this.text(var1, 10, Color.rgb(220, 252, 231), false);
      var5.setGravity(17);
      var3.addView(var4);
      var3.addView(var5);
      LinearLayout.LayoutParams var6 = new LinearLayout.LayoutParams(0, -2, 1.0F);
      var6.setMargins(this.dp(3), 0, this.dp(3), 0);
      var3.setLayoutParams(var6);
      return var3;
   }

   private View navBar() {
      LinearLayout var1 = this.row();
      var1.setPadding(this.dp(4), this.dp(4), this.dp(4), this.dp(4));
      var1.setBackground(this.round(-1, 18, LINE));
      LinearLayout.LayoutParams var2 = new LinearLayout.LayoutParams(-1, -2);
      var2.setMargins(0, 0, 0, this.dp(14));
      var1.setLayoutParams(var2);
      var1.addView(this.navButton("Dashboard", 0));
      var1.addView(this.navButton("Bins", 1));
      var1.addView(this.navButton("Map", 2));
      var1.addView(this.navButton("Alerts", 3));
      return var1;
   }

   private View navButton(String var1, final int var2) {
      TextView var3 = this.text(var1, 13, this.page == var2 ? -1 : SLATE, true);
      var3.setGravity(17);
      var3.setPadding(this.dp(8), this.dp(10), this.dp(8), this.dp(10));
      var3.setBackground(this.round(this.page == var2 ? GREEN : 0, 14));
      var3.setOnClickListener(new View.OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.page = var2;
            MainActivity.this.selectedBinId = null;
            MainActivity.this.renderCurrent();
         }
      });
      LinearLayout.LayoutParams var4 = new LinearLayout.LayoutParams(0, -2, 1.0F);
      var4.setMargins(this.dp(2), 0, this.dp(2), 0);
      var3.setLayoutParams(var4);
      return var3;
   }

   private View kpiGrid(Stats var1) {
      LinearLayout var2 = this.col();
      LinearLayout var3 = this.row();
      var3.addView(this.kpi("Fleet Health", var1.health + "%", "Operational confidence", var1.health >= 80 ? GREEN : AMBER));
      var3.addView(this.kpi("Avg Fill", var1.avg + "%", "Across active network", var1.avg >= 80 ? RED : (var1.avg >= 50 ? AMBER : GREEN)));
      LinearLayout var4 = this.row();
      var4.addView(this.kpi("Online Bins", var1.online + "/" + var1.total, "Telemetry in last 30 sec", GREEN));
      var4.addView(this.kpi("Critical", String.valueOf(var1.critical), "Immediate collection", var1.critical > 0 ? RED : GREEN));
      var2.addView(var3);
      var2.addView(var4);
      return var2;
   }

   private View kpi(String var1, String var2, String var3, int var4) {
      LinearLayout var5 = this.panel();
      var5.setPadding(this.dp(14), this.dp(13), this.dp(14), this.dp(13));
      var5.addView(this.text(var1, 11, MUTED, true));
      TextView var6 = this.text(var2, 26, var4, true);
      var6.setPadding(0, this.dp(5), 0, this.dp(2));
      var5.addView(var6);
      var5.addView(this.text(var3, 10, MUTED, false));
      LinearLayout.LayoutParams var7 = new LinearLayout.LayoutParams(0, -2, 1.0F);
      var7.setMargins(this.dp(3), 0, this.dp(3), this.dp(8));
      var5.setLayoutParams(var7);
      return var5;
   }

   private View alertCard(Bin var1) {
      LinearLayout var2 = this.panel();
      var2.setBackground(this.round(RED_SOFT, 18, Color.rgb(254, 205, 211)));
      LinearLayout var3 = this.row();
      var3.addView(this.text(var1.online ? "Collection required" : "Device offline", 15, RED, true), new LinearLayout.LayoutParams(0, -2, 1.0F));
      var3.addView(this.statusChip(this.status(var1), this.colorFor(var1)));
      var2.addView(var3);
      TextView var4 = this.text(var1.name + " | " + var1.place + " | " + var1.route, 12, SLATE, false);
      var4.setPadding(0, this.dp(6), 0, this.dp(8));
      var2.addView(var4);
      var2.addView(this.fillBar(var1.fill, this.colorFor(var1)));
      return var2;
   }

   private View alertHistoryCard(String var1) {
      LinearLayout var2 = this.panel();
      var2.setBackground(this.round(PANEL, 18, LINE));
      TextView var3 = this.text(var1, 13, DARK, true);
      var3.setPadding(0, 0, 0, this.dp(4));
      var2.addView(var3);
      var2.addView(this.text("Stored on this phone", 11, MUTED, false));
      return var2;
   }

   private View operationsCard(Stats var1) {
      LinearLayout var2 = this.panel();
      var2.addView(this.text("Network readiness", 15, DARK, true));
      var2.addView(this.metricLine("Fleet health", var1.health + "%", var1.health, var1.health >= 80 ? GREEN : AMBER));
      var2.addView(this.metricLine("Collection pressure", var1.critical + " urgent bins", Math.min(100, var1.critical * 25), var1.critical > 0 ? RED : GREEN));
      var2.addView(this.metricLine("Average fill", var1.avg + "% capacity used", var1.avg, var1.avg >= 80 ? RED : (var1.avg >= 50 ? AMBER : GREEN)));
      return var2;
   }

   private View metricLine(String var1, String var2, int var3, int var4) {
      LinearLayout var5 = this.col();
      var5.setPadding(0, this.dp(10), 0, 0);
      LinearLayout var6 = this.row();
      var6.addView(this.text(var1, 12, MUTED, true), new LinearLayout.LayoutParams(0, -2, 1.0F));
      var6.addView(this.text(var2, 12, var4, true));
      var5.addView(var6);
      var5.addView(this.fillBar(var3, var4));
      return var5;
   }

   private View binCard(Bin var1) {
      LinearLayout var2 = this.panel();
      LinearLayout var3 = this.row();
      var3.addView(this.text(var1.name, 16, DARK, true), new LinearLayout.LayoutParams(0, -2, 1.0F));
      var3.addView(this.statusChip(this.status(var1), this.colorFor(var1)));
      var2.addView(var3);
      TextView var4 = this.text(var1.id + " | " + var1.place + " | " + var1.ward + " | " + var1.route, 12, MUTED, false);
      var4.setPadding(0, this.dp(5), 0, this.dp(10));
      var2.addView(var4);
      LinearLayout var5 = this.row();
      var5.addView(this.text("Fill level", 12, MUTED, true), new LinearLayout.LayoutParams(0, -2, 1.0F));
      var5.addView(this.text(var1.fill + "%", 18, this.colorFor(var1), true));
      var2.addView(var5);
      var2.addView(this.fillBar(var1.fill, this.colorFor(var1)));
      String var6 = var1.battery < 0 ? "-" : var1.battery + "%";
      String var7 = var1.rssi == -999 ? "-" : var1.rssi + " dBm";
      TextView var8 = this.text("Battery " + var6 + " | Signal " + var7, 11, MUTED, false);
      var8.setPadding(0, this.dp(8), 0, 0);
      var2.addView(var8);
      TextView var9 = this.text("Tap for full bin status", 11, GREEN_DARK, true);
      var9.setPadding(0, this.dp(8), 0, 0);
      var2.addView(var9);
      return var2;
   }

   private ProgressBar fillBar(int var1, int var2) {
      ProgressBar var3 = new ProgressBar(this, (AttributeSet)null, 16842872);
      var3.setMax(100);
      var3.setProgress(Math.max(0, Math.min(100, var1)));
      var3.setProgressTintList(ColorStateList.valueOf(var2));
      var3.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(226, 232, 240)));
      LinearLayout.LayoutParams var4 = new LinearLayout.LayoutParams(-1, this.dp(8));
      var4.setMargins(0, this.dp(7), 0, 0);
      var3.setLayoutParams(var4);
      return var3;
   }

   private TextView section(String var1) {
      TextView var2 = this.text(var1, 18, DARK, true);
      var2.setPadding(0, this.dp(10), 0, this.dp(10));
      return var2;
   }

   private View detailLine(String var1, String var2) {
      LinearLayout var3 = this.row();
      var3.setPadding(0, this.dp(6), 0, this.dp(6));
      var3.addView(this.text(var1, 12, MUTED, true), new LinearLayout.LayoutParams(0, -2, 1.0F));
      TextView var4 = this.text(var2, 13, DARK, true);
      var4.setGravity(5);
      var3.addView(var4, new LinearLayout.LayoutParams(0, -2, 2.0F));
      return var3;
   }

   private Bin findBin(String var1) {
      for(int var2 = 0; var2 < this.currentBins.size(); ++var2) {
         Bin var3 = (Bin)this.currentBins.get(var2);
         if (var3.id.equals(var1)) {
            return var3;
         }
      }

      return null;
   }

   private void openGoogleMaps(Bin var1) {
      String var2 = var1.lat + "," + var1.lng;
      Uri var3 = Uri.parse("google.navigation:q=" + var2 + "&mode=d");
      Intent var4 = new Intent(Intent.ACTION_VIEW, var3);
      var4.setPackage("com.google.android.apps.maps");
      if (var4.resolveActivity(this.getPackageManager()) == null) {
         var4 = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + var2));
      }

      this.startActivity(var4);
   }

   private String fillMessage(Bin var1) {
      if (!var1.online) {
         return "Device offline";
      } else if (var1.fill >= 90) {
         return "Bin is almost full";
      } else if (var1.fill >= 80) {
         return "Bin is full";
      } else {
         return var1.fill >= 50 ? "Bin is filling" : "Bin has capacity";
      }
   }

   private String actionText(Bin var1) {
      if (!var1.online) {
         return "Check device connectivity";
      } else if (var1.fill >= 80) {
         return "Send collection vehicle";
      } else {
         return var1.fill >= 50 ? "Plan collection soon" : "No action needed";
      }
   }

   private View banner(String var1, int var2, int var3) {
      TextView var4 = this.text(var1, 13, var2, true);
      var4.setPadding(this.dp(14), this.dp(12), this.dp(14), this.dp(12));
      var4.setBackground(this.round(var3, 16));
      LinearLayout.LayoutParams var5 = new LinearLayout.LayoutParams(-1, -2);
      var5.setMargins(0, 0, 0, this.dp(12));
      var4.setLayoutParams(var5);
      return var4;
   }

   private LinearLayout panel() {
      LinearLayout var1 = this.col();
      var1.setPadding(this.dp(16), this.dp(14), this.dp(16), this.dp(14));
      var1.setBackground(this.round(PANEL, 18, LINE));
      var1.setElevation((float)this.dp(2));
      LinearLayout.LayoutParams var2 = new LinearLayout.LayoutParams(-1, -2);
      var2.setMargins(0, 0, 0, this.dp(10));
      var1.setLayoutParams(var2);
      return var1;
   }

   private TextView statusChip(String var1, int var2) {
      TextView var3 = this.text(var1, 11, -1, true);
      var3.setGravity(17);
      var3.setPadding(this.dp(10), this.dp(5), this.dp(10), this.dp(5));
      var3.setBackground(this.round(var2, 999));
      return var3;
   }

   private LinearLayout row() {
      LinearLayout var1 = new LinearLayout(this);
      var1.setOrientation(0);
      var1.setGravity(16);
      return var1;
   }

   private LinearLayout col() {
      LinearLayout var1 = new LinearLayout(this);
      var1.setOrientation(1);
      return var1;
   }

   private TextView text(String var1, int var2, int var3, boolean var4) {
      TextView var5 = new TextView(this);
      var5.setText(var1);
      var5.setTextSize((float)var2);
      var5.setTextColor(this.textColor(var3));
      var5.setIncludeFontPadding(true);
      if (var4) {
         var5.setTypeface(Typeface.DEFAULT, 1);
      }

      return var5;
   }

   private GradientDrawable round(int var1, int var2) {
      GradientDrawable var3 = new GradientDrawable();
      var3.setColor(this.bgColor(var1));
      var3.setCornerRadius((float)this.dp(var2));
      return var3;
   }

   private GradientDrawable round(int var1, int var2, int var3) {
      GradientDrawable var4 = this.round(var1, var2);
      var4.setStroke(this.dp(1), this.lineColor(var3));
      return var4;
   }

   private GradientDrawable gradient(int var1, int var2) {
      GradientDrawable var3 = new GradientDrawable(Orientation.TL_BR, new int[]{this.darkTheme ? Color.rgb(2, 44, 34) : var1, this.darkTheme ? Color.rgb(4, 120, 87) : var2});
      var3.setCornerRadius((float)this.dp(24));
      return var3;
   }

   private int textColor(int var1) {
      if (!this.darkTheme) return var1;
      if (var1 == DARK) return Color.rgb(248, 250, 252);
      if (var1 == SLATE) return Color.rgb(203, 213, 225);
      if (var1 == MUTED) return Color.rgb(148, 163, 184);
      if (var1 == GREEN_DARK) return Color.rgb(134, 239, 172);
      if (var1 == Color.rgb(220, 252, 231)) return Color.rgb(187, 247, 208);
      return var1;
   }

   private int bgColor(int var1) {
      if (!this.darkTheme) return var1;
      if (var1 == BG) return Color.rgb(8, 13, 24);
      if (var1 == PANEL || var1 == -1) return Color.rgb(15, 23, 42);
      if (var1 == LINE) return Color.rgb(51, 65, 85);
      if (var1 == GREEN_SOFT) return Color.rgb(6, 78, 59);
      if (var1 == RED_SOFT) return Color.rgb(88, 28, 28);
      if (var1 == AMBER_SOFT) return Color.rgb(92, 64, 19);
      return var1;
   }

   private int lineColor(int var1) {
      if (!this.darkTheme) return var1;
      if (var1 == LINE) return Color.rgb(51, 65, 85);
      if (var1 == GREEN) return Color.rgb(20, 184, 166);
      return var1;
   }

   private String status(Bin var1) {
      if (!var1.online) {
         return "Offline";
      } else if (var1.fill >= 80) {
         return "Critical";
      } else {
         return var1.fill >= 50 ? "Watch" : "Optimal";
      }
   }

   private int colorFor(Bin var1) {
      if (!var1.online) {
         return Color.rgb(148, 163, 184);
      } else if (var1.fill >= 80) {
         return RED;
      } else {
         return var1.fill >= 50 ? AMBER : GREEN;
      }
   }

   private void captureAlertHistory(List var1) {
      boolean var2 = false;
      Set var3 = new HashSet(this.alertHistory);

      for(int var4 = 0; var4 < var1.size(); ++var4) {
         Bin var5 = (Bin)var1.get(var4);
         if (var5.fill >= 80 || !var5.online) {
            if (this.shouldNotifyInApp(var5)) {
               this.showBinNotification(var5);
            }

            String var6 = DateFormat.getDateTimeInstance(3, 3).format(new Date()) + " - " + var5.id + " " + this.status(var5) + " at " + var5.place + " (" + var5.fill + "%)";
            String var7 = var5.id + " " + this.status(var5) + " at " + var5.place + " (" + var5.fill + "%)";
            boolean var8 = false;

            for(int var9 = 0; var9 < this.alertHistory.size(); ++var9) {
               if (((String)this.alertHistory.get(var9)).contains(var7)) {
                  var8 = true;
                  break;
               }
            }

            if (!var8 && !var3.contains(var6)) {
               this.alertHistory.add(0, var6);
               var2 = true;
            }
         }
      }

      while(this.alertHistory.size() > 40) {
         this.alertHistory.remove(this.alertHistory.size() - 1);
         var2 = true;
      }

      if (var2) {
         this.saveAlertHistory();
      }
   }

   private boolean shouldNotifyInApp(Bin var1) {
      SharedPreferences var2 = this.getSharedPreferences("smartbin_inapp_alerts", 0);
      String var3 = var1.id + ":" + this.status(var1) + ":" + var1.fill;
      long var4 = System.currentTimeMillis();
      long var6 = var2.getLong(var3, 0L);
      if (var4 - var6 < 30L * 60L * 1000L) {
         return false;
      }

      var2.edit().putLong(var3, var4).apply();
      return true;
   }

   private void loadAlertHistory() {
      String var1 = this.getSharedPreferences("smartbin_alerts", 0).getString("history", "");
      this.alertHistory.clear();
      if (var1.length() > 0) {
         String[] var2 = var1.split("\\n");

         for(int var3 = 0; var3 < var2.length; ++var3) {
            if (var2[var3].trim().length() > 0) {
               this.alertHistory.add(var2[var3]);
            }
         }
      }
   }

   private void saveAlertHistory() {
      StringBuilder var1 = new StringBuilder();

      for(int var2 = 0; var2 < this.alertHistory.size(); ++var2) {
         if (var2 > 0) {
            var1.append('\n');
         }

         var1.append((String)this.alertHistory.get(var2));
      }

      SharedPreferences.Editor var3 = this.getSharedPreferences("smartbin_alerts", 0).edit();
      var3.putString("history", var1.toString());
      var3.apply();
   }

   private void createNotificationChannel() {
      if (Build.VERSION.SDK_INT >= 26) {
         NotificationChannel var1 = new NotificationChannel("smartbin_alerts", "SmartBin Alerts", NotificationManager.IMPORTANCE_HIGH);
         var1.setDescription("Full bin and device alert notifications");
         NotificationManager var2 = (NotificationManager)this.getSystemService(NOTIFICATION_SERVICE);
         if (var2 != null) {
            var2.createNotificationChannel(var1);
         }
      }
   }

   private void requestNotificationPermission() {
      if (Build.VERSION.SDK_INT >= 33 && this.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
         this.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1025);
      }
   }

   private void scheduleBackgroundAlerts() {
      PeriodicWorkRequest var1 = new PeriodicWorkRequest.Builder(SmartBinAlertWorker.class, 15L, TimeUnit.MINUTES).build();
      WorkManager.getInstance(this).enqueueUniquePeriodicWork("smartbin_background_alerts", ExistingPeriodicWorkPolicy.UPDATE, var1);
      OneTimeWorkRequest var2 = new OneTimeWorkRequest.Builder(SmartBinAlertWorker.class).build();
      WorkManager.getInstance(this).enqueueUniqueWork("smartbin_background_alerts_now", ExistingWorkPolicy.REPLACE, var2);
      SmartBinAlarmReceiver.schedule(this);
      Intent var3 = new Intent(this, SmartBinAlertService.class);
      if (Build.VERSION.SDK_INT >= 26) {
         this.startForegroundService(var3);
      } else {
         this.startService(var3);
      }
   }

   private void showBinNotification(Bin var1) {
      if (Build.VERSION.SDK_INT >= 33 && this.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
         return;
      }

      String var2 = this.cleanPlace(var1.place);
      String var3 = !var1.online ? var2 + " bin is offline" : var2 + " bin is full";
      String var4 = var1.id + " needs attention. Fill level: " + var1.fill + "%";
      Intent var5 = new Intent(this, MainActivity.class);
      var5.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      PendingIntent var6 = PendingIntent.getActivity(this, Math.abs(var1.id.hashCode()), var5, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
      Notification.Builder var7 = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "smartbin_alerts") : new Notification.Builder(this);
      var7.setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(var3).setContentText(var4).setContentIntent(var6).setAutoCancel(true).setShowWhen(true);
      if (Build.VERSION.SDK_INT >= 21) {
         var7.setColor(GREEN);
      }

      NotificationManager var8 = (NotificationManager)this.getSystemService(NOTIFICATION_SERVICE);
      if (var8 != null) {
         var8.notify(Math.abs((var1.id + this.status(var1) + var1.fill).hashCode()), var7.build());
      }
   }

   private String cleanPlace(String var1) {
      if (var1 == null || var1.trim().length() == 0) {
         return "SmartBin";
      }

      String var2 = var1.replace('_', ' ').trim();
      return var2.length() == 0 ? "SmartBin" : var2;
   }

   private Stats stats(List var1) {
      Stats var2 = new Stats();
      var2.total = var1.size();
      int var3 = 0;

      for(int var4 = 0; var4 < var1.size(); ++var4) {
         Bin var5 = (Bin)var1.get(var4);
         if (var5.online) {
            ++var2.online;
         }

         if (var5.fill >= 80) {
            ++var2.critical;
         }

         var3 += var5.fill;
      }

      var2.avg = var2.total == 0 ? 0 : Math.round((float)var3 / (float)var2.total);
      var2.health = var2.total == 0 ? 0 : Math.max(0, Math.round((float)var2.online * 100.0F / (float)var2.total - (float)(var2.critical * 8)));
      return var2;
   }

   private int dp(int var1) {
      return (int)((float)var1 * this.getResources().getDisplayMetrics().density + 0.5F);
   }

   private List demoBins() {
      ArrayList var1 = new ArrayList();
      var1.add(new Bin("SB-1025", "Madhapur Road 36", "Madhapur", "Ward 12", "Route 12", 100, 55, -65, true, 17.4483, 78.3915));
      var1.add(new Bin("SB-0876", "Jubilee Hills Post", "Jubilee", "Ward 07", "Route 07", 95, 62, -71, true, 17.4326, 78.4071));
      var1.add(new Bin("SB-1345", "Ameerpet Metro", "Ameerpet", "Ward 09", "Route 03", 72, 48, -78, true, 17.4375, 78.4483));
      var1.add(new Bin("SB-1765", "Dilsukhnagar Main", "Dilsukhnagar", "Ward 21", "Route 09", 85, 74, -66, true, 17.3688, 78.5247));
      var1.add(new Bin("SB-1920", "Kothapet Market", "Kothapet", "Ward 22", "Route 09", 30, 80, -60, false, 17.3683, 78.5476));
      return var1;
   }

   private double defaultLat(String var1) {
      String var2 = var1 == null ? "" : var1.toLowerCase();
      if (var2.contains("jubilee")) return 17.4326;
      if (var2.contains("ameerpet")) return 17.4375;
      if (var2.contains("dilsukhnagar")) return 17.3688;
      if (var2.contains("kothapet")) return 17.3683;
      if (var2.contains("hitech")) return 17.4483;
      return 17.4483;
   }

   private double defaultLng(String var1) {
      String var2 = var1 == null ? "" : var1.toLowerCase();
      if (var2.contains("jubilee")) return 78.4071;
      if (var2.contains("ameerpet")) return 78.4483;
      if (var2.contains("dilsukhnagar")) return 78.5247;
      if (var2.contains("kothapet")) return 78.5476;
      if (var2.contains("hitech")) return 78.3915;
      return 78.3915;
   }

   private static class Stats {
      int total;
      int online;
      int critical;
      int avg;
      int health;

      private Stats() {
      }
   }

   private class SplashView extends View {
      private final Paint paint = new Paint(1);
      private final long started = System.currentTimeMillis();

      SplashView() {
         super(MainActivity.this);
         this.setBackgroundColor(MainActivity.this.bgColor(BG));
      }

      protected void onDraw(Canvas var1) {
         super.onDraw(var1);
         float var2 = (float)this.getWidth();
         float var3 = (float)this.getHeight();
         float var4 = Math.min(1.0F, (float)(System.currentTimeMillis() - this.started) / 1900.0F);
         float var5 = var2 / 2.0F;
         float var6 = var3 / 2.0F - (float)MainActivity.this.dp(42);
         float var7 = (float)MainActivity.this.dp(94);
         float var8 = (float)Math.sin((double)(var4 * 3.1415927F));
         this.paint.setStyle(Paint.Style.FILL);
         this.paint.setColor(MainActivity.this.darkTheme ? Color.rgb(5, 46, 38) : GREEN_SOFT);
         var1.drawCircle(var5, var6, var7 * (1.58F + var8 * 0.10F), this.paint);
         this.paint.setColor(MainActivity.this.darkTheme ? Color.rgb(8, 80, 62) : Color.rgb(187, 247, 208));
         var1.drawCircle(var5, var6, var7 * (1.32F + var8 * 0.06F), this.paint);
         this.paint.setColor(Color.argb(MainActivity.this.darkTheme ? 90 : 130, 16, 185, 129));
         for(int varSpark = 0; varSpark < 9; ++varSpark) {
            double varAngle = (double)varSpark * 0.6981317D + (double)var4 * 1.8D;
            float varRing = var7 * (1.75F + 0.12F * (float)Math.sin((double)var4 * 6.0D + (double)varSpark));
            var1.drawCircle(var5 + (float)Math.cos(varAngle) * varRing, var6 + (float)Math.sin(varAngle) * varRing, (float)MainActivity.this.dp(varSpark % 3 == 0 ? 3 : 2), this.paint);
         }

         this.paint.setColor(GREEN);
         float var9 = var5 - var7 * 0.38F;
         float var10 = var6 - var7 * 0.22F;
         float var11 = var5 + var7 * 0.38F;
         float var12 = var6 + var7 * 0.34F;
         this.paint.setShadowLayer((float)MainActivity.this.dp(12), 0.0F, (float)MainActivity.this.dp(6), Color.argb(80, 5, 150, 105));
         var1.drawRoundRect(var9, var10, var11, var12, (float)MainActivity.this.dp(16), (float)MainActivity.this.dp(16), this.paint);
         this.paint.clearShadowLayer();
         this.paint.setColor(GREEN_DARK);
         float var13 = -42.0F * (float)Math.sin((double)(var4 * 1.5707964F));
         var1.save();
         var1.rotate(var13, var5 - var7 * 0.32F, var10 - var7 * 0.05F);
         var1.drawRoundRect(var5 - var7 * 0.42F, var10 - var7 * 0.10F, var5 + var7 * 0.42F, var10 + var7 * 0.02F, (float)MainActivity.this.dp(8), (float)MainActivity.this.dp(8), this.paint);
         var1.restore();
         this.paint.setColor(-1);
         this.paint.setStrokeWidth((float)MainActivity.this.dp(5));
         this.paint.setStyle(Paint.Style.STROKE);
         var1.drawLine(var5 - var7 * 0.17F, var10 + var7 * 0.11F, var5 - var7 * 0.17F, var12 - var7 * 0.14F, this.paint);
         var1.drawLine(var5, var10 + var7 * 0.11F, var5, var12 - var7 * 0.14F, this.paint);
         var1.drawLine(var5 + var7 * 0.17F, var10 + var7 * 0.11F, var5 + var7 * 0.17F, var12 - var7 * 0.14F, this.paint);
         this.paint.setStyle(Paint.Style.FILL);
         this.paint.setTypeface(Typeface.DEFAULT_BOLD);
         this.paint.setTextAlign(Paint.Align.CENTER);
         this.paint.setTextSize((float)MainActivity.this.dp(34));
         float varReveal = Math.min(1.0F, var4 * 1.25F);
         float varLift = (1.0F - varReveal) * (float)MainActivity.this.dp(18);
         this.paint.setAlpha((int)(255.0F * varReveal));
         this.paint.setColor(MainActivity.this.textColor(DARK));
         var1.drawText("Smart", var5 - (float)MainActivity.this.dp(30), var6 + var7 * 1.58F + varLift, this.paint);
         this.paint.setColor(GREEN);
         var1.drawText("Bin", var5 + (float)MainActivity.this.dp(64), var6 + var7 * 1.58F + varLift, this.paint);
         this.paint.setTextSize((float)MainActivity.this.dp(15));
         this.paint.setColor(MainActivity.this.textColor(MUTED));
         var1.drawText("Control", var5, var6 + var7 * 1.84F + varLift, this.paint);
         this.paint.setAlpha(255);
         if (var4 < 1.0F) {
            this.invalidate();
         }
      }
   }

   private class MapBinMarker extends View {
      private final int markerColor;
      private final Paint paint = new Paint(1);

      MapBinMarker(int var2) {
         super(MainActivity.this);
         this.markerColor = var2;
         this.setLayerType(1, (Paint)null);
      }

      protected void onDraw(Canvas var1) {
         super.onDraw(var1);
         float var2 = (float)this.getWidth();
         float var3 = (float)this.getHeight();
         float var4 = Math.min(var2, var3);
         float var5 = var2 / 2.0F;
         float var6 = var3 / 2.0F;
         this.paint.setStyle(Paint.Style.FILL);
         this.paint.setColor(this.markerColor);
         this.paint.setShadowLayer((float)MainActivity.this.dp(4), 0.0F, (float)MainActivity.this.dp(2), Color.argb(70, 0, 0, 0));
         var1.drawCircle(var5, var6, var4 * 0.45F, this.paint);
         this.paint.clearShadowLayer();
         this.paint.setStyle(Paint.Style.STROKE);
         this.paint.setStrokeWidth(var4 * 0.055F);
         this.paint.setStrokeCap(Paint.Cap.ROUND);
         this.paint.setStrokeJoin(Paint.Join.ROUND);
         this.paint.setColor(-1);
         var1.drawCircle(var5, var6, var4 * 0.45F, this.paint);
         float var7 = var4 * 0.18F;
         float var8 = var4 * 0.04F;
         float var9 = var4 * 0.24F;
         float var10 = var4 * 0.28F;
         var1.drawLine(var5 - var9, var6 - var7, var5 + var9, var6 - var7, this.paint);
         var1.drawLine(var5 - var8, var6 - var10, var5 + var8, var6 - var10, this.paint);
         var1.drawLine(var5 - var4 * 0.17F, var6 - var7, var5 - var4 * 0.12F, var6 + var4 * 0.22F, this.paint);
         var1.drawLine(var5 + var4 * 0.17F, var6 - var7, var5 + var4 * 0.12F, var6 + var4 * 0.22F, this.paint);
         var1.drawLine(var5 - var4 * 0.12F, var6 + var4 * 0.22F, var5 + var4 * 0.12F, var6 + var4 * 0.22F, this.paint);
         var1.drawLine(var5 - var4 * 0.055F, var6 - var4 * 0.04F, var5 - var4 * 0.055F, var6 + var4 * 0.14F, this.paint);
         var1.drawLine(var5 + var4 * 0.055F, var6 - var4 * 0.04F, var5 + var4 * 0.055F, var6 + var4 * 0.14F, this.paint);
      }
   }

   private static class Bin {
      final String id;
      final String name;
      final String place;
      final String ward;
      final String route;
      final int fill;
      final int battery;
      final int rssi;
      final boolean online;
      final double lat;
      final double lng;

      Bin(String var1, String var2, String var3, String var4, String var5, int var6, int var7, int var8, boolean var9, double var10, double var12) {
         this.id = var1;
         this.name = var2;
         this.place = var3;
         this.ward = var4;
         this.route = var5;
         this.fill = var6;
         this.battery = var7;
         this.rssi = var8;
         this.online = var9;
         this.lat = var10;
         this.lng = var12;
      }
   }
}
