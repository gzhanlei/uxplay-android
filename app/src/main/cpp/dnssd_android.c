/* dnssd_android.c — dnssd.h 的安卓实现
 * 广播交给 Java NsdManager（通过 jni_bridge 缓存的方法 ID 回调），
 * TXT 属性由 Java 侧按 UxPlay dnssd.c 的内容构造。 */
#include "dnssd.h"
#include "jni_bridge.h"
#include "utils.h"
#include <stdlib.h>
#include <string.h>

struct dnssd_s {
    char *name;
    int name_len;
    char *hw_addr;
    int hw_addr_len;
    char *pk;
    unsigned long features1, features2;
    unsigned char pin_pw;
    unsigned short raop_port;
    unsigned short airplay_port;
};

dnssd_t *dnssd_init(const char *name, int name_len, const char *hw_addr, int hw_addr_len, int *error, unsigned char pin_pw) {
    if (error) *error = DNSSD_ERROR_NOERROR;
    dnssd_t *d = calloc(1, sizeof(dnssd_t));
    if (!d) { if (error) *error = DNSSD_ERROR_OUTOFMEM; return NULL; }
    d->name_len = name_len;
    d->name = calloc(1, name_len + 1);
    memcpy(d->name, name, name_len);
    d->hw_addr_len = hw_addr_len;
    d->hw_addr = calloc(1, hw_addr_len);
    memcpy(d->hw_addr, hw_addr, hw_addr_len);
    d->pin_pw = pin_pw;
    /* UxPlay 默认能力位：含镜像(bit7/25/27)与音频(bit9)——原版 dnssd.c 从
       dnssdint.h 的 FEATURES_1/2 解析，重写时漏掉会导致 /info 报 features=0，
       macOS 据此把设备归类为纯音频（正是此前"无法镜像"的根因） */
    d->features1 = 0x5A7FFEE6;
    d->features2 = 0x0;
    return d;
}

void dnssd_destroy(dnssd_t *dnssd) {
    if (!dnssd) return;
    free(dnssd->name);
    free(dnssd->hw_addr);
    free(dnssd->pk);
    free(dnssd);
}

int dnssd_register_raop(dnssd_t *dnssd, unsigned short port) {
    dnssd->raop_port = port;
    JNIEnv *env = NULL;
    bridge_attach_env(&env);
    if (!env || !g_bridge_cls || !g_m_register_raop) return DNSSD_ERROR_NOERROR;
    char hw[18] = {0};
    if (utils_hwaddr_raop(hw, sizeof(hw), dnssd->hw_addr, dnssd->hw_addr_len) < 0)
        return DNSSD_ERROR_HWADDRLEN;
    char servname[256];
    snprintf(servname, sizeof(servname), "%s@%s", hw, dnssd->name ? dnssd->name : "");
    jstring jname = (*env)->NewStringUTF(env, servname);
    jstring jtype = (*env)->NewStringUTF(env, "_raop._tcp");
    jstring jdev = (*env)->NewStringUTF(env, hw);
    jstring jpk = (*env)->NewStringUTF(env, dnssd->pk ? dnssd->pk : "");
    (*env)->CallStaticVoidMethod(env, g_bridge_cls, g_m_register_raop, jname, jtype, jdev, port, jpk);
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, jtype);
    (*env)->DeleteLocalRef(env, jdev);
    (*env)->DeleteLocalRef(env, jpk);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return DNSSD_ERROR_NOERROR;
}

int dnssd_register_airplay(dnssd_t *dnssd, unsigned short port) {
    dnssd->airplay_port = port;
    JNIEnv *env = NULL;
    bridge_attach_env(&env);
    if (!env || !g_bridge_cls || !g_m_register_airplay) return DNSSD_ERROR_NOERROR;
    char hw[18] = {0};
    if (utils_hwaddr_airplay(hw, sizeof(hw), dnssd->hw_addr, dnssd->hw_addr_len) < 0)
        return DNSSD_ERROR_HWADDRLEN;
    jstring jname = (*env)->NewStringUTF(env, dnssd->name ? dnssd->name : "AirPlay");
    jstring jtype = (*env)->NewStringUTF(env, "_airplay._tcp");
    jstring jdev = (*env)->NewStringUTF(env, hw);
    jstring jpk = (*env)->NewStringUTF(env, dnssd->pk ? dnssd->pk : "");
    (*env)->CallStaticVoidMethod(env, g_bridge_cls, g_m_register_airplay, jname, jtype, jdev, port, jpk);
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, jtype);
    (*env)->DeleteLocalRef(env, jdev);
    (*env)->DeleteLocalRef(env, jpk);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return DNSSD_ERROR_NOERROR;
}

void dnssd_unregister_raop(dnssd_t *dnssd) { dnssd->raop_port = 0; }
void dnssd_unregister_airplay(dnssd_t *dnssd) { dnssd->airplay_port = 0; }

const char *dnssd_get_raop_txt(dnssd_t *dnssd, int *length) { if (length) *length = 0; return NULL; }
const char *dnssd_get_airplay_txt(dnssd_t *dnssd, int *length) { if (length) *length = 0; return NULL; }
const char *dnssd_get_name(dnssd_t *dnssd, int *length) { if (length) *length = dnssd->name_len; return dnssd->name; }
const char *dnssd_get_hw_addr(dnssd_t *dnssd, int *length) { if (length) *length = dnssd->hw_addr_len; return dnssd->hw_addr; }
void dnssd_set_airplay_features(dnssd_t *dnssd, int bit, int val) { (void) dnssd; (void) bit; (void) val; }
uint64_t dnssd_get_airplay_features(dnssd_t *dnssd) { return ((uint64_t) dnssd->features2) << 32 | dnssd->features1; }
void dnssd_set_pk(dnssd_t *dnssd, char *pk_str) { dnssd->pk = pk_str; }
