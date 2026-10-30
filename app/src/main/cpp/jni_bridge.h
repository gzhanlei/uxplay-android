/* jni_bridge.h — 缓存的 NativeBridge 类与方法 ID（JNI_OnLoad 时初始化，供任意线程使用） */
#ifndef JNI_BRIDGE_H
#define JNI_BRIDGE_H
#include <jni.h>

extern JavaVM *g_vm;
extern jclass g_bridge_cls;
extern jmethodID g_m_register_raop;   /* (String name, String type, String devid, int port, String pk) */
extern jmethodID g_m_register_airplay;
extern jmethodID g_m_push_video;      /* (byte[] nal, int len, boolean h265) */
extern jmethodID g_m_push_audio;      /* (byte[] frame, int len, byte ct) */
extern jmethodID g_m_set_volume;      /* (float) */

void bridge_attach_env(JNIEnv **env);  /* AttachCurrentThread（幂等） */

#endif
