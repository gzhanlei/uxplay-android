package com.codelphi.airplay;

import android.view.Surface;

/** C ↔ Java 桥：C 侧回调推流数据，Java 侧驱动 MediaCodec/AudioTrack/NsdManager。 */
public class NativeBridge {

    public interface Listener {
        void onAirplayRegister(String name, int port, String deviceId, String pk);
        void onRaopRegister(String name, int port, String deviceId, String pk);
        void onLog(String msg);
    }

    /** 新镜像会话开始（首帧视频到达）时回调，用于后台自动唤起画面 */
    public interface SessionListener { void onSessionStart(); }

    /** 镜像会话结束（客户端断开连接）时回调，用于清除残留画面回到等待界面 */
    public interface SessionEndListener { void onSessionEnd(); }

    private static volatile Listener listener;
    private static volatile SessionListener sessionListener;
    private static volatile SessionEndListener sessionEndListener;
    private static volatile Surface surface;
    private static final VideoSink videoSink = new VideoSink();
    private static final AudioSink audioSink = new AudioSink();
    private static volatile long lastVideoFrame = 0;
    private static volatile boolean videoH265;

    public static void setListener(Listener l) { listener = l; }
    public static void setSessionListener(SessionListener l) { sessionListener = l; }
    public static void setSessionEndListener(SessionEndListener l) { sessionEndListener = l; }
    public static void clearSessionEndListener(SessionEndListener l) {
        if (sessionEndListener == l) sessionEndListener = null;
    }
    public static void setSurface(Surface s) { surface = s; }

    // ---- C 回调入口 ----
    public static void cLog(String msg) {
        if (listener != null) listener.onLog(msg);
    }

    public static void cRegisterAirplay(String name, String type, String deviceId, int port, String pk) {
        android.util.Log.i("NativeBridge", "注册 " + type + " " + name + ":" + port);
        if (listener != null) listener.onAirplayRegister(name, port, deviceId, pk);
    }

    public static void cRegisterRaop(String name, String type, String deviceId, int port, String pk) {
        android.util.Log.i("NativeBridge", "注册 " + type + " " + name + ":" + port);
        if (listener != null) listener.onRaopRegister(name, port, deviceId, pk);
    }

    public static void cPushVideo(byte[] nal, int len, boolean h265) {
        videoH265 = h265;
        boolean wasIdle = lastVideoFrame == 0 || System.currentTimeMillis() - lastVideoFrame > 2000;
        lastVideoFrame = System.currentTimeMillis();
        if (wasIdle && sessionListener != null) {
            try { sessionListener.onSessionStart(); } catch (Exception ignored) { }
        }
        byte[] data = new byte[len];
        System.arraycopy(nal, 0, data, 0, len);
        videoSink.push(data, h265);
    }

    public static void cSessionEnd() {
        android.util.Log.i("NativeBridge", "镜像会话结束");
        lastVideoFrame = 0;
        if (sessionEndListener != null) {
            try { sessionEndListener.onSessionEnd(); } catch (Exception ignored) { }
        }
    }

    /** 距最后一帧视频的时间（毫秒）；0 表示从未收到 */
    public static long videoIdleMs() {
        return lastVideoFrame == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastVideoFrame;
    }

    public static void cPushAudio(byte[] aac, int len, byte ct) {
        byte[] data = new byte[len];
        System.arraycopy(aac, 0, data, 0, len);
        if (ct == 0) {  /* PCM 直接播放 */
            audioSink.pushPcm(data);
        } else {
            audioSink.push(data);
        }
    }

    public static void cSetVolume(float volume) { audioSink.setVolume(volume); }

    public static Surface getSurface() { return surface; }
    public static boolean isVideoH265() { return videoH265; }
    public static VideoSink getVideoSink() { return videoSink; }
    public static AudioSink getAudioSink() { return audioSink; }

    static { System.loadLibrary("airplay-jni"); }

    public static native void nativeSetInfo(String name, String mac);
    public static native void nativeSetFilesDir(String dir);
    public static native int nativeStart();
    public static native void nativeStop();
    public static native void nativeConnectSurface(Surface surface);
    public static native void nativeDisconnectSurface();
}
