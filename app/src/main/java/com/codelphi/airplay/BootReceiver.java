package com.codelphi.airplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：拉起 AirPlay 前台服务，设备重启后投屏接收立即可用 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            try {
                AirPlayService.start(context);
                android.util.Log.i("BootReceiver", "已自启 AirPlay 服务");
            } catch (Exception e) {
                android.util.Log.e("BootReceiver", "自启失败", e);
            }
        }
    }
}
