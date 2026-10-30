package com.codelphi.airplay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 音频解码驱动：镜像音频为 AAC-ELD（ct=8），MediaCodec 解码 → AudioTrack。
 *
 * 节奏控制要点（爆音排查结论：解码 PCM 本身干净，杂音来自供数不平稳导致的 underrun）：
 *  - 严格 FIFO：input buffer 不可用时帧挂起在 pending，绝不放回队尾（否则多帧时乱序出爆音）；
 *  - 预缓冲：攒够 PREBUFFER_FRAMES 帧再 play()，吸收 WiFi 收包突发；
 *  - underrun / 丢帧计数每 5s 打一条日志，供 dumpsys 交叉验证。
 */
public class AudioSink {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    /* AirPlay 镜像 AAC-ELD 的 AudioSpecificConfig（44100Hz 立体声，与 UxPlay gstreamer caps 一致：f8e85000） */
    private static final byte[] ASC = {(byte) 0xF8, (byte) 0xE8, 0x50, 0x00};

    private static final int QUEUE_CAP = 512;              /* AAC 帧上限 ≈ 5.8s，纯防抖 */
    private static final int PREBUFFER_BYTES = 1920 * 8;   /* 攒 8 帧解码 PCM（≈87ms）后才开始播 */
    private static final int DUMP_CAP = SAMPLE_RATE * 4 * 30; /* 30s，覆盖语音段 */

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(QUEUE_CAP);
    private final Object trackLock = new Object();
    private MediaCodec codec;
    private volatile AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    private volatile long droppedFrames;
    private volatile long pushedFrames;
    private long lastUnderrun;
    private long lastHead;
    private final StringBuilder sizeHist = new StringBuilder();
    public static String dumpDir = "/data/local/tmp";

    public synchronized boolean isRunning() { return running; }

    public synchronized void start() {
        stop();
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectELD);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(ASC));
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(fmt, null, null, 0);
            codec.start();
            int bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT) * 8;
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufSize)
                    .build();
            /* 不在此处 play()：等预缓冲攒够再起播 */
            running = true;
            droppedFrames = 0;
            pushedFrames = 0;
            thread = new Thread(this::loop, "audiosink");
            thread.start();
            android.util.Log.i("AudioSink", "启动: trackBuffer=" + bufSize + "B (" + (bufSize / 4 * 1000 / SAMPLE_RATE) + "ms)");
        } catch (Exception e) {
            android.util.Log.e("AudioSink", "启动失败", e);
        }
    }

    public void push(byte[] aac) {
        if (!running) return;
        pushedFrames++;
        if (!queue.offer(aac)) droppedFrames++;
    }

    /** PCM 直播（ct=0）不经解码 */
    public void pushPcm(byte[] pcm) {
        if (!running) return;
        playTrack();
        writePcm(pcm, pcm.length);
    }

    private void playTrack() {
        synchronized (trackLock) {
            if (track != null && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
        }
    }

    /** AirPlay 音量为 dB：0=最大，-144=静音（raop_rtp_set_volume 钳制区间），转线性增益给 AudioTrack */
    public void setVolume(float db) {
        synchronized (trackLock) {
            if (track != null) track.setVolume((float) Math.pow(10, db / 20.0));
        }
    }

    public synchronized void stop() {
        running = false;
        WorkerThread.stop(thread);
        thread = null;
        if (codec != null) { codec.stop(); codec.release(); codec = null; }
        synchronized (trackLock) {
            if (track != null) { track.stop(); track.release(); track = null; }
        }
        queue.clear();
    }

    private void writePcm(byte[] pcm, int len) {
        int offset = 0;
        while (running && offset < len) {
            int written;
            synchronized (trackLock) {
                if (track == null) return;
                written = track.write(pcm, offset, len - offset, AudioTrack.WRITE_NON_BLOCKING);
            }
            if (written < 0) {
                android.util.Log.w("AudioSink", "PCM 写入失败: " + written);
                return;
            }
            if (written == 0) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            offset += written;
        }
    }

    private void logStats() {
        long u = 0;
        long head;
        synchronized (trackLock) {
            if (track == null) return;
            try { u = track.getUnderrunCount(); } catch (Exception ignored) { }
            head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        }
        long headDelta = head - lastHead;   /* 负数说明 track 被重建/回绕，忽略 */
        lastHead = head;
        android.util.Log.i("AudioSink", "统计: 队列=" + queue.size() + " 收=" + pushedFrames
                + " 丢=" + droppedFrames + " underrun=" + u + " (+" + (u - lastUnderrun) + ")"
                + " 播放头+" + headDelta + "帧/5s(应≈" + (SAMPLE_RATE * 5) + ")"
                + " 尺寸[" + sizeHist + "]");
        lastUnderrun = u;
        sizeHist.setLength(0);
    }

    private void loop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Exception ignored) { }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pending = null;                 /* input buffer 未就绪时挂起的帧，保序 */
        byte[] pcmBuf = new byte[8192];
        long lastStat = 0;
        boolean prebuffering = true;
        int prebuffered = 0;
        java.io.File dumpFile = new java.io.File(dumpDir, "airplay_pcm.raw");
        java.io.FileOutputStream dump = null;
        int dumpLen = 0;
        while (running) {
            try {
                byte[] frame = (pending != null) ? pending : queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                boolean fed = false;
                if (frame != null) {
                    int inIdx = codec.dequeueInputBuffer(0);
                    if (inIdx < 0) {
                        pending = frame;                       /* 保序挂起，绝不放回队尾 */
                        Thread.sleep(2);
                    } else {
                        pending = null;
                        ByteBuffer in = codec.getInputBuffer(inIdx);
                        in.clear();
                        in.put(frame);
                        codec.queueInputBuffer(inIdx, 0, frame.length, System.nanoTime() / 1000, 0);
                        fed = true;
                    }
                }
                boolean got = false;
                while (running) {
                    int outIdx = codec.dequeueOutputBuffer(info, fed ? 0 : 10000);
                    if (outIdx >= 0) {
                        ByteBuffer out = codec.getOutputBuffer(outIdx);
                        if (out != null && info.size > 0) {
                            if (pcmBuf.length < info.size) pcmBuf = new byte[info.size];
                            out.position(info.offset);
                            out.get(pcmBuf, 0, info.size);
                            if (prebuffering) {
                                prebuffered += info.size;
                                if (prebuffered >= PREBUFFER_BYTES) {
                                    playTrack();
                                    prebuffering = false;
                                    android.util.Log.i("AudioSink", "预缓冲完成 " + prebuffered + "B，开始播放");
                                }
                            }
                            writePcm(pcmBuf, info.size);
                            got = true;
                            /* 诊断：解码 PCM 落盘（30s 覆盖语音段）+ 输出帧尺寸直方图（验证 SBR：5760B=1440采样） */
                            if (dumpLen < DUMP_CAP) {
                                try {
                                    if (dump == null) dump = new java.io.FileOutputStream(dumpFile);
                                    dump.write(pcmBuf, 0, info.size);
                                    dumpLen += info.size;
                                    dump.flush();
                                } catch (Exception ignored) { }
                            }
                            if (sizeHist.indexOf("x" + info.size) < 0 && sizeHist.length() < 80) {
                                if (sizeHist.length() > 0) sizeHist.append(' ');
                                sizeHist.append(info.size);
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false);
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        android.util.Log.i("AudioSink", "输出格式: " + codec.getOutputFormat());
                    } else break;
                }
                if (!fed && !got) continue;   /* poll 已带 50ms 等待 */
                long now = System.currentTimeMillis();
                if (now - lastStat > 5000) {
                    lastStat = now;
                    logStats();
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (running) android.util.Log.e("AudioSink", "解码异常", e);
            }
        }
        if (dump != null) try { dump.close(); } catch (Exception ignored) { }
    }
}
