package com.codelphi.airplay;

import android.view.Surface;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/** 视频解码驱动：C 侧 NALU → AvCC→AnnexB 转换 → MediaCodec（csd 惰性配置）→ Surface */
public class VideoSink {

    /** 视频真实分辨率回调（UI 层据此做 letterbox 等比缩放，避免拉伸） */
    public interface VideoSizeListener { void onVideoSize(int width, int height); }

    private static volatile VideoSizeListener sizeListener;
    private int lastReportedW, lastReportedH;

    public static void setVideoSizeListener(VideoSizeListener l) { sizeListener = l; }

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(16);
    private MediaCodec codec;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean csdConfigured;
    private Surface surface;
    private boolean h265;

    private byte[] vpsNal;  /* H.265：含起始码的 VPS */
    private byte[] spsNal;  /* 含起始码的 SPS */
    private byte[] ppsNal;  /* 含起始码的 PPS */

    public synchronized boolean isRunning() { return running; }

    public synchronized void start(boolean h265, Surface surface) {
        stop();
        if (this.h265 != h265) clearParameterSets();
        this.h265 = h265;
        this.surface = surface;
        try {
            createCodec();
            running = true;
            thread = new Thread(this::loop, "videosink");
            thread.start();
        } catch (Exception e) {
            android.util.Log.e("VideoSink", "启动失败", e);
        }
    }

    private void clearParameterSets() {
        vpsNal = null;
        spsNal = null;
        ppsNal = null;
        csdConfigured = false;
    }

    private byte[] joinParameterSets() {
        byte[] csd = new byte[vpsNal.length + spsNal.length + ppsNal.length];
        System.arraycopy(vpsNal, 0, csd, 0, vpsNal.length);
        System.arraycopy(spsNal, 0, csd, vpsNal.length, spsNal.length);
        System.arraycopy(ppsNal, 0, csd, vpsNal.length + spsNal.length, ppsNal.length);
        return csd;
    }

    private void createCodec() throws Exception {
        String mime = h265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat fmt = MediaFormat.createVideoFormat(mime, 1920, 1080);
        if (csdConfigured) {
            if (h265) {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(joinParameterSets()));
            } else {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(spsNal));
                fmt.setByteBuffer("csd-1", ByteBuffer.wrap(ppsNal));
            }
        }
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, surface, null, 0);
        codec.start();
    }

    private void reconfigureWithCsd() {
        csdConfigured = true;
        running = false;
        queue.clear();
        if (thread != null) { thread.interrupt(); try { thread.join(500); } catch (InterruptedException ignored) { } thread = null; }
        if (codec != null) { try { codec.stop(); codec.release(); } catch (Exception ignored) { } codec = null; }
        try {
            createCodec();
            running = true;
            thread = new Thread(this::loop, "videosink");
            thread.start();
            android.util.Log.i("VideoSink", "已用 csd 重配置解码器");
        } catch (Exception e) {
            running = false;
            android.util.Log.e("VideoSink", "csd 重配置失败", e);
        }
    }

    public void push(byte[] nal, boolean h265) {
        if (this.h265 != h265 || !running) {
            /* 新会话或编码变化时重启；只有同编码才复用缓存的参数集 */
            if (surface == null || !surface.isValid()) return;
            start(h265, surface);
            if (!running) return;
        }
        byte[] annexb = avccToAnnexB(nal);
        extractParameterSets(annexb);
        if (!csdConfigured && hasParameterSets()) {
            reconfigureWithCsd();   /* MTK 等硬解器需要 configure 时携带 csd */
        }
        if (running) queue.offer(annexb);
    }

    private boolean hasParameterSets() {
        return spsNal != null && ppsNal != null && (!h265 || vpsNal != null);
    }

    /** H.264 提取 SPS/PPS；H.265 提取 VPS/SPS/PPS（均含起始码） */
    private void extractParameterSets(byte[] b) {
        int i = 0;
        while (i < b.length - 4) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1) {
                int next = findNextStartCode(b, i + 4);
                int nalEnd = next < 0 ? b.length : next;
                saveParameterSet(b[i + 4] & 0xff, b, i, nalEnd);
                i = i + 4;
            } else {
                i++;
            }
        }
    }

    private void saveParameterSet(int header, byte[] b, int from, int to) {
        int type;
        int vpsType = -1;
        int spsType = 7;
        int ppsType = 8;
        if (h265) {
            type = (header >> 1) & 0x3f;
            vpsType = 32;
            spsType = 33;
            ppsType = 34;
        } else {
            type = header & 0x1f;
        }
        if (type == vpsType && vpsNal == null) vpsNal = slice(b, from, to);
        if (type == spsType && spsNal == null) spsNal = slice(b, from, to);
        if (type == ppsType && ppsNal == null) ppsNal = slice(b, from, to);
    }

    private static int findNextStartCode(byte[] b, int from) {
        for (int i = from; i < b.length - 3; i++)
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1) return i;
        return -1;
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, r.length);
        return r;
    }

    /** UxPlay 交来的是 AvCC 格式（4 字节大端长度前缀），转 Annex-B（00 00 00 01 起始码） */
    private static byte[] avccToAnnexB(byte[] avcc) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(avcc.length + 16);
        java.nio.ByteBuffer wrap = java.nio.ByteBuffer.wrap(avcc);
        byte[] sc = {0, 0, 0, 1};
        while (wrap.remaining() >= 4) {
            int len = wrap.getInt();
            if (len <= 0 || len > wrap.remaining()) return avcc;  /* 已是 Annex-B 或格式异常 */
            out.write(sc, 0, 4);
            byte[] nal = new byte[len];
            wrap.get(nal);
            out.write(nal, 0, len);
        }
        return out.toByteArray();
    }

    public synchronized void stop() {
        running = false;
        /* 同编码的参数集刻意保留：Surface 重建后带缓存 csd 启动可立即出画 */
        WorkerThread.stop(thread);
        thread = null;
        if (codec != null) { codec.stop(); codec.release(); codec = null; }
        queue.clear();
    }

    /** 从输出格式解析实际显示尺寸：优先 crop 裁剪框（硬解常带对齐 padding），否则退回 width/height */
    private static int[] displaySizeOf(MediaFormat f) {
        int w = -1, h = -1;
        try {
            if (f.containsKey("crop-right") && f.containsKey("crop-left"))
                w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
            else if (f.containsKey("width")) w = f.getInteger("width");
            if (f.containsKey("crop-bottom") && f.containsKey("crop-top"))
                h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
            else if (f.containsKey("height")) h = f.getInteger("height");
        } catch (Exception ignored) { }
        return new int[]{w, h};
    }

    /** 分辨率没变化不重复通知；iPhone 横竖屏切换、iPad 接入都会带新分辨率进来 */
    private void reportVideoSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (w == lastReportedW && h == lastReportedH) return;
        lastReportedW = w;
        lastReportedH = h;
        VideoSizeListener l = sizeListener;
        if (l != null) l.onVideoSize(w, h);
    }

    /** 热循环：按 FIFO 喂入一帧后榨干输出；输入缓冲不可用时保留 pending，绝不重新排队乱序 */
    private void loop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Exception ignored) { }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pending = null;
        long ts = 0;
        while (running) {
            try {
                boolean fed = false;
                if (pending == null) pending = queue.poll();
                if (pending != null) {
                    int inIdx = codec.dequeueInputBuffer(0);
                    if (inIdx >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIdx);
                        in.clear();
                        in.put(pending);
                        codec.queueInputBuffer(inIdx, 0, pending.length, ts += 33367, 0);
                        pending = null;
                        fed = true;
                    }
                }
                /* 榨干全部输出 */
                boolean got = false;
                while (running) {
                    int outIdx = codec.dequeueOutputBuffer(info, fed ? 0 : 10000);
                    if (outIdx >= 0) {
                        codec.releaseOutputBuffer(outIdx, true);
                        got = true;
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!fed) Thread.sleep(10);
                        break;
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f = codec.getOutputFormat();
                        int[] disp = displaySizeOf(f);
                        android.util.Log.i("VideoSink", "格式: " + f + " → 显示 " + disp[0] + "x" + disp[1]);
                        reportVideoSize(disp[0], disp[1]);
                    } else break;
                }
                if (!fed && !got) Thread.sleep(10);   /* 空闲降温 */
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (running) android.util.Log.e("VideoSink", "解码异常", e);
            }
        }
    }
}
