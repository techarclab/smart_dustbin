package com.smartbin.control;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class SmartBinAlertWorker extends Worker {
   public SmartBinAlertWorker(Context var1, WorkerParameters var2) {
      super(var1, var2);
   }

   public Result doWork() {
      try {
         SmartBinAlertEngine.checkAndNotify(this.getApplicationContext());
         return Result.success();
      } catch (Exception var1) {
         return Result.retry();
      }
   }
}
