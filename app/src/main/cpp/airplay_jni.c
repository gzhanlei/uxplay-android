/* airplay_jni.c — UxPlay 主循环的安卓移植（镜像 V1）
 * 流程：raop_init(回调) → raop_init2(生成 ed25519 密钥) → raop_start_httpd → dnssd 注册 → 运行
 * 渲染：video/audio_process 回调 → jni_bridge → Java MediaCodec/AudioTrack
 */
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <ctype.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <android/log.h>

#include "dnssd.h"
#include "raop.h"
#include "stream.h"
#include "logger.h"

typedef unsigned int guint;
#include "video_renderer.h"
#include "audio_renderer.h"
#include "jni_bridge.h"

JavaVM *g_vm = NULL;
jclass g_bridge_cls = NULL;
jmethodID g_m_register_raop = NULL;
jmethodID g_m_register_airplay = NULL;
jmethodID g_m_push_video = NULL;
jmethodID g_m_push_audio = NULL;
jmethodID g_m_set_volume = NULL;
jmethodID g_m_session_end = NULL;

static raop_t *raop = NULL;
static pthread_t uxplay_thread;
static pthread_mutex_t lifecycle_mutex = PTHREAD_MUTEX_INITIALIZER;
static atomic_int running = ATOMIC_VAR_INIT(0);
static int thread_started = 0;
static int stopping = 0;
static unsigned short raop_port = 0, airplay_port = 0;
static unsigned char last_ct = 3;
static char g_mac[32] = "00:00:00:00:00:00";
static unsigned char g_hw_addr[6] = {0};
static char g_name[128] = "AirPlay-Receiver";
static char g_filesdir[256] = "/data/local/tmp";

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AirPlay", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AirPlay", __VA_ARGS__)

void bridge_attach_env(JNIEnv **env) {
    if (!g_vm) { *env = NULL; return; }
    if ((*g_vm)->GetEnv(g_vm, (void **) env, JNI_VERSION_1_6) == JNI_EDETACHED)
        (*g_vm)->AttachCurrentThread(g_vm, env, NULL);
}

/* ---------- raop 回调 ---------- */

static void conn_init(void *cls) { LOGI("客户端连接"); (void) cls; }
static void conn_destroy(void *cls) {
    (void) cls;
    LOGI("客户端断开");
    JNIEnv *env = NULL;
    bridge_attach_env(&env);
    if (!env || !g_bridge_cls || !g_m_session_end) return;
    (*env)->CallStaticVoidMethod(env, g_bridge_cls, g_m_session_end);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}
static void conn_reset(void *cls, int reason) { (void) cls; (void) reason; }

static void audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    (void) cls; (void) ntp;
    if (data->ct != last_ct) {
        last_ct = data->ct;
        unsigned char ct = data->ct;
        audio_renderer_start(&ct);
    }
    audio_renderer_render_buffer(data->data, &data->data_len, &data->seqnum, &data->ntp_time_remote);
}

static void video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls; (void) ntp;
    video_renderer_render_buffer(data->data, &data->data_len, &data->nal_count, &data->ntp_time_remote);
}

static void video_reset(void *cls, reset_type_t reset_type) {
    (void) cls; (void) reset_type;
    video_renderer_flush();
}

static void log_callback(void *cls, int level, const char *msg) {
    (void) cls;
    if (!msg) return;
    int prio = ANDROID_LOG_INFO;
    if (level <= LOGGER_ERR) prio = ANDROID_LOG_ERROR;
    else if (level == LOGGER_WARNING) prio = ANDROID_LOG_WARN;
    else if (level >= LOGGER_DEBUG) prio = ANDROID_LOG_DEBUG;
    __android_log_print(prio, "AirPlayStack", "%s", msg);
}

static void audio_set_volume(void *cls, float volume) {
    (void) cls;
    JNIEnv *env = NULL;
    bridge_attach_env(&env);
    if (!env || !g_bridge_cls || !g_m_set_volume) return;
    (*env)->CallStaticVoidMethod(env, g_bridge_cls, g_m_set_volume, (jfloat) volume);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* ---------- 其余回调的空实现（raop/airplay 内部存在无判空调用点） ---------- */

static void video_pause(void *cls) { (void) cls; }
static void video_resume(void *cls) { (void) cls; }
static void conn_feedback(void *cls) { (void) cls; }
static void conn_teardown(void *cls, bool *t96, bool *t110) { (void) cls; (void) t96; (void) t110; }
static void audio_flush(void *cls) { (void) cls; audio_renderer_flush(); }
static void video_flush(void *cls) { (void) cls; video_renderer_flush(); }
static double audio_set_client_volume(void *cls) { (void) cls; return 0.0; }
static void audio_set_metadata(void *cls, const void *b, int l) { (void) cls; (void) b; (void) l; }
static void audio_set_coverart(void *cls, const void *b, int l) { (void) cls; (void) b; (void) l; }
static void audio_stop_coverart_rendering(void *cls) { (void) cls; }
static void audio_remote_control_id(void *cls, const char *d, const char *h) { (void) cls; (void) d; (void) h; }
static void audio_set_progress(void *cls, uint32_t *s, uint32_t *c, uint32_t *e) { (void) cls; (void) s; (void) c; (void) e; }
static void audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    (void) cls; (void) spf; (void) usingScreen; (void) isMedia; (void) audioFormat;
    if (ct) {
        unsigned char c = *ct;
        audio_renderer_start(&c);
    }
}
static void video_report_size(void *cls, float *ws, float *hs, float *w, float *h) {
    (void) cls;
    if (ws) *ws = 1920; if (hs) *hs = 1080; if (w) *w = 1920; if (h) *h = 1080;
}
static void mirror_video_activity(void *cls, double *tx) { (void) cls; (void) tx; }
static void report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void) cls; (void) deviceid; (void) model; (void) name;
    if (admit) *admit = true;   /* 接受所有客户端 */
}
static void display_pin(void *cls, char *pin) { (void) cls; (void) pin; }
static void register_client(void *cls, const char *d, const char *p, const char *n) { (void) cls; (void) d; (void) p; (void) n; }
static bool check_register(void *cls, const char *pk) { (void) cls; (void) pk; return true; }
static const char *passwd(void *cls, int *len) { (void) cls; if (len) *len = 0; return NULL; }
static void export_dacp(void *cls, const char *ar, const char *di) { (void) cls; (void) ar; (void) di; }
static int video_set_codec(void *cls, video_codec_t codec) {
    (void) cls;
    return video_renderer_choose_codec(false, codec == VIDEO_CODEC_H265);
}
static void on_video_play(void *cls, const char *loc, float sp) { (void) cls; (void) loc; (void) sp; }
static void on_video_scrub(void *cls, float p) { (void) cls; (void) p; }
static void on_video_rate(void *cls, float r) { (void) cls; (void) r; }
static void on_video_stop(void *cls) { (void) cls; }
static void on_video_acquire_playback_info(void *cls, playback_info_t *pv) { (void) cls; (void) pv; }
static float on_video_playlist_remove(void *cls) { (void) cls; return 0.0f; }

/* ---------- 主循环 ---------- */

static void *uxplay_thread_fn(void *arg) {
    (void) arg;
    raop_t *server = NULL;
    dnssd_t *discovery = NULL;
    LOGI("uxplay 启动, name=%s mac=%s", g_name, g_mac);

    video_renderer_init(NULL, g_name, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                        false, true, false, false, 1, NULL);
    unsigned char ct = 3;
    audio_renderer_init(NULL, NULL, NULL, NULL, NULL);
    audio_renderer_start(&ct);
    video_renderer_start();

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = conn_init;
    cbs.conn_destroy = conn_destroy;
    cbs.conn_reset = conn_reset;
    cbs.conn_feedback = conn_feedback;
    cbs.conn_teardown = conn_teardown;
    cbs.audio_process = audio_process;
    cbs.video_process = video_process;
    cbs.audio_flush = audio_flush;
    cbs.video_flush = video_flush;
    cbs.video_pause = video_pause;
    cbs.video_resume = video_resume;
    cbs.audio_set_client_volume = audio_set_client_volume;
    cbs.audio_set_volume = audio_set_volume;
    cbs.audio_set_metadata = audio_set_metadata;
    cbs.audio_set_coverart = audio_set_coverart;
    cbs.audio_stop_coverart_rendering = audio_stop_coverart_rendering;
    cbs.audio_remote_control_id = audio_remote_control_id;
    cbs.audio_set_progress = audio_set_progress;
    cbs.audio_get_format = audio_get_format;
    cbs.video_report_size = video_report_size;
    cbs.mirror_video_activity = mirror_video_activity;
    cbs.report_client_request = report_client_request;
    cbs.display_pin = display_pin;
    cbs.register_client = register_client;
    cbs.check_register = check_register;
    cbs.passwd = passwd;
    cbs.export_dacp = export_dacp;
    cbs.video_set_codec = video_set_codec;
    cbs.on_video_play = on_video_play;
    cbs.on_video_scrub = on_video_scrub;
    cbs.on_video_rate = on_video_rate;
    cbs.on_video_stop = on_video_stop;
    cbs.on_video_acquire_playback_info = on_video_acquire_playback_info;
    cbs.on_video_playlist_remove = on_video_playlist_remove;
    cbs.video_reset = video_reset;

    if (!atomic_load(&running)) goto cleanup;
    server = raop_init(&cbs);
    if (!server) { LOGE("raop_init 失败"); goto cleanup; }
    raop_set_log_callback(server, log_callback, NULL);
    raop_set_log_level(server, 7);   /* 全量调试日志 */

    char keyfile[256];
    snprintf(keyfile, sizeof(keyfile), "%s/airplay_keys", g_filesdir);
    if (raop_init2(server, 1, g_mac, keyfile)) {   /* nohold=1：新客户端可抢占 */
        LOGE("raop_init2 失败");
        goto cleanup;
    }
    if (!atomic_load(&running)) goto cleanup;
    pthread_mutex_lock(&lifecycle_mutex);
    raop = server;
    pthread_mutex_unlock(&lifecycle_mutex);
    if (!atomic_load(&running)) goto cleanup;

    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(server, tcp);
    raop_set_udp_ports(server, udp);

    raop_port = raop_get_port(server);
    if (raop_start_httpd(server, &raop_port) != 1) {
        LOGE("raop_start_httpd 失败");
        goto cleanup;
    }
    raop_set_port(server, raop_port);
    airplay_port = raop_port;

    int err = 0;
    discovery = dnssd_init(g_name, strlen(g_name), (const char *) g_hw_addr, sizeof(g_hw_addr), &err, 0);
    if (!discovery) { LOGE("dnssd_init 失败: %d", err); goto cleanup; }
    /* 镜像特性位（照抄 uxplay 默认） */
    dnssd_set_airplay_features(discovery, 1, 1);
    dnssd_set_airplay_features(discovery, 2, 1);
    dnssd_set_airplay_features(discovery, 5, 1);
    dnssd_set_airplay_features(discovery, 6, 1);
    dnssd_set_airplay_features(discovery, 7, 1);
    dnssd_set_airplay_features(discovery, 9, 1);
    dnssd_set_airplay_features(discovery, 10, 1);
    dnssd_set_airplay_features(discovery, 11, 1);
    dnssd_set_airplay_features(discovery, 42, 1);  /* H.265 */
    raop_set_dnssd(server, discovery);

    if (dnssd_register_raop(discovery, raop_port)) LOGE("raop 注册失败");
    if (dnssd_register_airplay(discovery, airplay_port)) LOGE("airplay 注册失败");

    LOGI("AirPlay 服务已广播: raop=%d airplay=%d", raop_port, airplay_port);
    while (atomic_load(&running) && raop_is_running(server)) sleep(1);
    LOGI("AirPlay 服务退出");

cleanup:
    pthread_mutex_lock(&lifecycle_mutex);
    if (raop == server) raop = NULL;
    pthread_mutex_unlock(&lifecycle_mutex);
    if (server) raop_destroy(server);
    if (discovery) dnssd_destroy(discovery);
    video_renderer_destroy();
    audio_renderer_destroy();
    atomic_store(&running, 0);
    return NULL;
}

/* ---------- JNI 导出 ---------- */

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/codelphi/airplay/NativeBridge");
    if (!cls) return JNI_ERR;
    g_bridge_cls = (jclass) (*env)->NewGlobalRef(env, cls);
    g_m_register_raop = (*env)->GetStaticMethodID(env, cls, "cRegisterRaop",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
    g_m_register_airplay = (*env)->GetStaticMethodID(env, cls, "cRegisterAirplay",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
    g_m_push_video = (*env)->GetStaticMethodID(env, cls, "cPushVideo", "([BIZ)V");
    g_m_push_audio = (*env)->GetStaticMethodID(env, cls, "cPushAudio", "([BIB)V");
    g_m_set_volume = (*env)->GetStaticMethodID(env, cls, "cSetVolume", "(F)V");
    g_m_session_end = (*env)->GetStaticMethodID(env, cls, "cSessionEnd", "()V");
    if (!g_m_register_raop || !g_m_register_airplay || !g_m_push_video || !g_m_push_audio || !g_m_set_volume || !g_m_session_end)
        return JNI_ERR;
    return JNI_VERSION_1_6;
}

static int parse_hw_addr(const char *mac, unsigned char out[6]) {
    if (!mac || strlen(mac) != 17) return -1;
    for (int i = 0; i < 17; i++) {
        if (i % 3 == 2) {
            if (mac[i] != ':') return -1;
        } else if (!isxdigit((unsigned char) mac[i])) {
            return -1;
        }
    }
    for (int i = 0; i < 6; i++) {
        char pair[3] = {mac[i * 3], mac[i * 3 + 1], 0};
        out[i] = (unsigned char) strtoul(pair, NULL, 16);
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_codelphi_airplay_NativeBridge_nativeSetInfo(JNIEnv *env, jclass cls,
                                                     jstring jname, jstring jmac) {
    (void) cls;
    if (atomic_load(&running) || !jname || !jmac) return;
    const char *n = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *m = (*env)->GetStringUTFChars(env, jmac, NULL);
    if (n && m) {
        unsigned char hw_addr[6];
        if (parse_hw_addr(m, hw_addr) == 0) {
            snprintf(g_name, sizeof(g_name), "%s", n);
            snprintf(g_mac, sizeof(g_mac), "%s", m);
            memcpy(g_hw_addr, hw_addr, sizeof(g_hw_addr));
        } else {
            LOGE("无效 MAC 地址");
        }
    }
    if (n) (*env)->ReleaseStringUTFChars(env, jname, n);
    if (m) (*env)->ReleaseStringUTFChars(env, jmac, m);
}

JNIEXPORT void JNICALL
Java_com_codelphi_airplay_NativeBridge_nativeSetFilesDir(JNIEnv *env, jclass cls, jstring jdir) {
    (void) cls;
    if (atomic_load(&running) || !jdir) return;
    const char *d = (*env)->GetStringUTFChars(env, jdir, NULL);
    if (d) {
        snprintf(g_filesdir, sizeof(g_filesdir), "%s", d);
        (*env)->ReleaseStringUTFChars(env, jdir, d);
    }
}

JNIEXPORT jint JNICALL
Java_com_codelphi_airplay_NativeBridge_nativeStart(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    pthread_mutex_lock(&lifecycle_mutex);
    if (thread_started && atomic_load(&running)) {
        pthread_mutex_unlock(&lifecycle_mutex);
        return 0;
    }
    if (thread_started && stopping) {
        pthread_mutex_unlock(&lifecycle_mutex);
        return -1;
    }
    if (thread_started) {
        stopping = 1;
        pthread_t previous = uxplay_thread;
        pthread_mutex_unlock(&lifecycle_mutex);
        int err = pthread_join(previous, NULL);
        pthread_mutex_lock(&lifecycle_mutex);
        thread_started = 0;
        stopping = 0;
        if (err) {
            pthread_mutex_unlock(&lifecycle_mutex);
            LOGE("回收 uxplay 线程失败: %d", err);
            return -1;
        }
    }
    atomic_store(&running, 1);
    int result = 0;
    if (pthread_create(&uxplay_thread, NULL, uxplay_thread_fn, NULL)) {
        atomic_store(&running, 0);
        result = -1;
    } else {
        thread_started = 1;
    }
    pthread_mutex_unlock(&lifecycle_mutex);
    return result;
}

JNIEXPORT void JNICALL
Java_com_codelphi_airplay_NativeBridge_nativeStop(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    pthread_mutex_lock(&lifecycle_mutex);
    if (!thread_started || stopping) {
        pthread_mutex_unlock(&lifecycle_mutex);
        return;
    }
    stopping = 1;
    atomic_store(&running, 0);
    if (raop) raop_stop_httpd(raop);
    pthread_t thread = uxplay_thread;
    pthread_mutex_unlock(&lifecycle_mutex);

    int err = pthread_join(thread, NULL);
    pthread_mutex_lock(&lifecycle_mutex);
    thread_started = 0;
    stopping = 0;
    pthread_mutex_unlock(&lifecycle_mutex);
    if (err) LOGE("等待 uxplay 线程退出失败: %d", err);
}

JNIEXPORT void JNICALL
Java_com_codelphi_airplay_NativeBridge_nativeConnectSurface(JNIEnv *env, jclass cls, jobject surface) {
    (void) env; (void) cls; (void) surface;  /* V1：解码由 Java MediaCodec 直接渲染到 Surface */
}

JNIEXPORT void JNICALL
Java_com_codelphi_airplay_NativeBridge_nativeDisconnectSurface(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
}
