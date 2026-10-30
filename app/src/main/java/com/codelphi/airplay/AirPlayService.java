package com.codelphi.airplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** AirPlay 接收前台服务：启动 C 运行时，用 NsdManager 注册 _raop/_airplay 服务 */
public class AirPlayService extends Service implements NativeBridge.Listener {

    private static final String CHANNEL = "airplay";
    private static final int NOTIF_ID = 2;
    public static volatile boolean running;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener raopListener, airplayListener;

    public static void start(Context context) {
        Intent intent = new Intent(context, AirPlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        startForeground(NOTIF_ID, notif("AirPlay 接收端运行中"));
        NativeBridge.setListener(this);
        /* 后台服务在会话接入时自动唤起画面（Android 10+ 后台启动 Activity 需 appops 授予悬浮窗权限） */
        NativeBridge.setSessionListener(() -> {
            try {
                Intent ui = new Intent(this, MirrorActivity.class);
                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(ui);
            } catch (Exception e) {
                android.util.Log.e("AirPlay", "自动唤起画面失败", e);
            }
        });

        String name = intent != null ? intent.getStringExtra("name") : null;
        String mac = intent != null ? intent.getStringExtra("mac") : null;
        /* 名称优先级：文件覆盖（filesDir/airplay_name）> intent extra > 默认 */
        if (name == null) name = readSmallFile("airplay_name");
        if (name == null) name = "AirPlay-Receiver";
        mac = mac != null ? mac : loadOrMakeMac();

        NativeBridge.nativeSetInfo(name, mac);
        NativeBridge.nativeSetFilesDir(getFilesDir().getAbsolutePath());
        AudioSink.dumpDir = getExternalFilesDir(null).getAbsolutePath();
        NativeBridge.nativeStart();
        return START_STICKY;
    }

    private NotificationManager nm() { return (NotificationManager) getSystemService(NOTIFICATION_SERVICE); }

    private Notification notif(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm().createNotificationChannel(new NotificationChannel(CHANNEL, "AirPlay", NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .build();
    }

    private String loadOrMakeMac() {
        File f = new File(getFilesDir(), "airplay_mac");
        try {
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) { in.read(b); }
                return new String(b).trim();
            }
            StringBuilder sb = new StringBuilder();
            java.security.SecureRandom r = new java.security.SecureRandom();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02X", r.nextInt(256))).append(i < 5 ? ":" : "");
            FileOutputStream out = new FileOutputStream(f);
            out.write(sb.toString().getBytes());
            out.close();
            return sb.toString();
        } catch (Exception e) {
            return "AA:BB:CC:DD:EE:FF";
        }
    }

    private String readSmallFile(String name) {
        File f = new File(getFilesDir(), name);
        try {
            if (!f.exists()) return null;
            byte[] b = new byte[(int) Math.min(f.length(), 256)];
            try (FileInputStream in = new FileInputStream(f)) { int n = in.read(b); if (n <= 0) return null; }
            String s = new String(b).trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** AirPlay 实例 UUID：每安装随机生成并持久化，避免同网络多台接收端 pi 冲突 */
    private String loadOrMakePi() {
        File f = new File(getFilesDir(), "airplay_pi");
        try {
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) { in.read(b); }
                String s = new String(b).trim();
                if (!s.isEmpty()) return s;
            }
            String pi = java.util.UUID.randomUUID().toString();
            FileOutputStream out = new FileOutputStream(f);
            out.write(pi.getBytes());
            out.close();
            return pi;
        } catch (Exception e) {
            return java.util.UUID.randomUUID().toString();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        NativeBridge.nativeStop();
        NativeBridge.setListener(null);
        NativeBridge.setSessionListener(null);
        unregister(raopListener);
        unregister(airplayListener);
        super.onDestroy();
    }

    private void unregister(NsdManager.RegistrationListener l) {
        if (nsdManager != null && l != null) nsdManager.unregisterService(l);
    }

    // ---- C 侧注册回调 → NsdManager ----

    @Override
    public void onAirplayRegister(String name, int port, String deviceId, String pk) {
        register("_airplay._tcp.", name, port, true, deviceId, pk);
    }

    @Override
    public void onRaopRegister(String name, int port, String deviceId, String pk) {
        register("_raop._tcp.", name, port, false, deviceId, pk);
    }

    private void register(String type, String name, int port, boolean airplay, String deviceId, String pk) {
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(type);
        info.setPort(port);
        /* TXT 属性按 UxPlay dnssd.c + 可被发现于 macOS 选择器的实例（乐播）对齐：
           pk/deviceid/vn/protovers/acl/rsf/gcgl 是选择器过滤的关键字段 */
        if (deviceId != null) info.setAttribute("deviceid", deviceId);
        if (pk != null) info.setAttribute("pk", pk);
        info.setAttribute("srcvers", "220.68");
        info.setAttribute("vv", "2");
        if (airplay) {
            /* 在实测可发现的位组合上增加 bit 42（H.265） */
            info.setAttribute("features", "0x527FFFF7,0x41E");
            info.setAttribute("model", "AppleTV3,2");
            info.setAttribute("flags", "0x4");
            info.setAttribute("pw", "false");
            info.setAttribute("pi", loadOrMakePi());
            info.setAttribute("vn", "65537");
            info.setAttribute("protovers", "1.1");
            info.setAttribute("acl", "0");
            info.setAttribute("rsf", "0");
            info.setAttribute("gcgl", "0");
        } else {
            info.setAttribute("ch", "2");
            info.setAttribute("cn", "0,1,2,3");
            info.setAttribute("da", "true");
            info.setAttribute("et", "0,3,5");
            info.setAttribute("ft", "0x5A7FFEE6,0x0");
            info.setAttribute("am", "AppleTV3,2");
            info.setAttribute("md", "0,1,2");
            info.setAttribute("rhd", "5.6.0.0");
            info.setAttribute("sf", "0x4");
            info.setAttribute("sr", "44100");
            info.setAttribute("ss", "16");
            info.setAttribute("sv", "false");
            info.setAttribute("tp", "UDP");
            info.setAttribute("txtvers", "1");
            info.setAttribute("vs", "220.68");
            info.setAttribute("vn", "65537");
            info.setAttribute("pw", "false");
        }
        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo i) {
                android.util.Log.i("AirPlay", "已注册: " + i.getServiceName() + " " + type);
            }
            @Override public void onRegistrationFailed(NsdServiceInfo i, int e) {
                android.util.Log.e("AirPlay", "注册失败: " + type + " err=" + e);
            }
            @Override public void onServiceUnregistered(NsdServiceInfo i) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
        };
        if (airplay) { airplayListener = listener; } else { raopListener = listener; }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        nm().notify(NOTIF_ID, notif("AirPlay 接收就绪（" + name + "）"));
    }

    @Override
    public void onLog(String msg) {
        nm().notify(NOTIF_ID, notif(msg));
    }
}
