/* video_renderer_android.c — 视频渲染器：H.264/H.265 NALU 经 JNI 推给 Java MediaCodec */
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <jni.h>
#include <android/log.h>

#ifndef __ANDROID_GLIB_COMPAT
#define __ANDROID_GLIB_COMPAT
typedef unsigned int guint;   /* UxPlay 渲染器头文件使用了 glib 的 guint */
#endif
#include "../../../../uxplay/renderers/video_renderer.h"
#include "jni_bridge.h"

extern JavaVM *g_vm;

static bool h265 = false;

void bridge_push_video_c(unsigned char *data, int len) {
    JNIEnv *env = NULL;
    bridge_attach_env(&env);
    if (!env || !g_bridge_cls || !g_m_push_video || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) { if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env); return; }
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) data);
    (*env)->CallStaticVoidMethod(env, g_bridge_cls, g_m_push_video, arr, len, (jboolean) h265);
    (*env)->DeleteLocalRef(env, arr);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

void video_renderer_init(logger_t *logger, const char *server_name, videoflip_t videoflip[2],
                         const char *parser, const char *rtp_pipeline, const char *decoder,
                         const char *converter, const char *videosink, const char *videosink_options,
                         bool initial_fullscreen, bool video_sync, bool h265_support, bool coverart_support,
                         guint playbin_version, const char *uri) {
    (void) logger; (void) server_name; (void) videoflip; (void) parser; (void) rtp_pipeline;
    (void) decoder; (void) converter; (void) videosink; (void) videosink_options;
    (void) initial_fullscreen; (void) video_sync; (void) coverart_support; (void) playbin_version; (void) uri;
}

int video_renderer_choose_codec(bool video_is_jpeg, bool video_is_h265) {
    h265 = video_is_h265;
    return video_is_h265 ? 1 : 0;
}

void video_renderer_start() {}
void video_renderer_stop() {}
void video_renderer_flush() {}

uint64_t video_renderer_render_buffer(unsigned char *data, int *data_len, int *nal_count, uint64_t *ntp_time) {
    (void) nal_count; (void) ntp_time;
    bridge_push_video_c(data, *data_len);
    return 0;
}

void video_renderer_set_device_model(const char *model, const char *name) { (void) model; (void) name; }
void video_renderer_set_track_metadata(const char *t, const char *a, const char *al) { (void) t; (void) a; (void) al; }
void video_renderer_pause() {}
void video_renderer_hls_ready() {}
void video_renderer_seek(float p) { (void) p; }
void video_renderer_set_start(float p) { (void) p; }
void video_renderer_resume() {}
int video_renderer_cycle() { return 0; }
bool video_renderer_is_paused() { return false; }
bool video_renderer_eos_watch() { return false; }
void video_renderer_display_jpeg(const void *data, int *data_len) { (void) data; (void) data_len; }
unsigned int video_renderer_listen(void *loop, int id) { (void) loop; (void) id; return 0; }
void video_renderer_destroy() {}
void video_renderer_size(float *ws, float *hs, float *w, float *h) { *ws = *hs = *w = *h = 0; }
bool waiting_for_x11_window() { return false; }
bool video_get_playback_info(double *d, double *p, double *ss, double *sd, float *r, bool *be, bool *bf) {
    if (d) *d = 0; if (p) *p = 0; if (ss) *ss = 0; if (sd) *sd = 0; if (r) *r = 1.0;
    if (be) *be = false; if (bf) *bf = false;
    return false;
}
