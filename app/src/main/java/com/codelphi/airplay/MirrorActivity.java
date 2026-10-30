package com.codelphi.airplay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MirrorActivity extends Activity {

    private SurfaceView surfaceView;
    private TextView status;
    private final Handler uiHandler = new Handler();
    private final NativeBridge.SessionEndListener sessionEndListener = this::showWaitingScreen;
    private long lastBack;

    /* 镜像中每秒检查一次：有帧 → 隐藏状态文字；无帧 → 显示等待提示。
       conn_destroy 正常通知会话结束；此处轮询是异常断开（无 FIN）时的兜底 */
    private boolean wasMirroring;
    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            boolean mirroring = NativeBridge.videoIdleMs() < 3000;
            if (wasMirroring && !mirroring) showWaitingScreen();
            wasMirroring = mirroring;
            status.setVisibility(mirroring ? View.GONE : View.VISIBLE);
            if (!mirroring) status.setText(R.string.waiting_for_connection);
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.mirror);

        surfaceView = findViewById(R.id.surface);
        status = findViewById(R.id.status);

        NativeBridge.setSessionEndListener(sessionEndListener);
        /* 解码器报出真实分辨率后按比例重排 SurfaceView：保持宽高比居中，多余部分留黑边，绝不拉伸 */
        VideoSink.setVideoSizeListener((w, h) -> runOnUiThread(() -> applyLetterbox(w, h)));

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                NativeBridge.setSurface(holder.getSurface());
                NativeBridge.nativeConnectSurface(holder.getSurface());
                VideoSink sink = NativeBridge.getVideoSink();
                if (!sink.isRunning()) sink.start(false, holder.getSurface());
                AudioSink sink2 = NativeBridge.getAudioSink();
                if (!sink2.isRunning()) sink2.start();          /* 音频解码驱动（此前漏启动） */
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                NativeBridge.nativeDisconnectSurface();
                NativeBridge.getVideoSink().stop();
                NativeBridge.getAudioSink().stop();
            }
        });

        uiHandler.postDelayed(uiTick, 1200);
        AirPlayService.start(this);
    }

    @Override
    public void onBackPressed() {
        if (System.currentTimeMillis() - lastBack < 2000) {
            stopService(new Intent(this, AirPlayService.class));
            finish();
        } else {
            lastBack = System.currentTimeMillis();
            Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        NativeBridge.clearSessionEndListener(sessionEndListener);   /* 静态槽位持 Activity 引用，销毁时清掉防泄漏 */
        VideoSink.setVideoSizeListener(null);   /* Activity 可重建，避免持着旧实例泄漏 */
        super.onDestroy();
    }

    /** letterbox 等比缩放：视频比例与屏幕一致时恢复满屏；不一致时居中 + 黑边（更宽→上下黑边，更窄→左右黑边） */
    private void applyLetterbox(int vw, int vh) {
        if (vw <= 0 || vh <= 0 || surfaceView == null) return;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int sw = dm.widthPixels, sh = dm.heightPixels;
        if (sw <= 0 || sh <= 0) return;
        double videoAr = (double) vw / vh;
        double screenAr = (double) sw / sh;
        int w, h;
        if (videoAr > screenAr) {
            w = sw;
            h = (int) Math.round(sw / videoAr);
        } else {
            h = sh;
            w = (int) Math.round(sh * videoAr);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        surfaceView.setLayoutParams(lp);
        android.util.Log.i("MirrorActivity", "letterbox: 视频 " + vw + "x" + vh + " → 显示 " + w + "x" + h + " (屏 " + sw + "x" + sh + ")");
    }

    /** Mac 退出镜像/断开：停掉解码器并清掉 Surface 上的残留帧，回退到等待连接画面。
       新会话首帧到达时 VideoSink.push 会自动重启解码器 */
    private void showWaitingScreen() {
        NativeBridge.getVideoSink().stop();
        runOnUiThread(() -> {
            clearSurfaceToBlack();
            status.setText(R.string.waiting_for_connection);
            status.setVisibility(View.VISIBLE);
        });
    }

    private void clearSurfaceToBlack() {
        try {
            SurfaceHolder h = surfaceView.getHolder();
            if (!h.getSurface().isValid()) return;
            android.graphics.Canvas c = h.lockCanvas(null);
            if (c == null) return;
            c.drawColor(android.graphics.Color.BLACK);
            h.unlockCanvasAndPost(c);
        } catch (Exception e) {
            android.util.Log.w("MirrorActivity", "清除残留画面失败", e);
        }
    }
}
