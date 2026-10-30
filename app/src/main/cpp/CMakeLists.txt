cmake_minimum_required(VERSION 3.18)
project(airplay LANGUAGES C)

set(UXPLAY ${CMAKE_SOURCE_DIR}/../../../../uxplay)
set(PLIST ${CMAKE_SOURCE_DIR}/../../../../deps/libplist)
set(OPENSSL_DIR ${CMAKE_SOURCE_DIR}/../../../../deps/openssl-build/armeabi-v7a)

# ---------- libplist 2.4.0 (纯 C, 静态) ----------
file(GLOB PLIST_SRC ${PLIST}/src/*.c ${PLIST}/libcnary/*.c)
list(REMOVE_ITEM PLIST_SRC ${PLIST}/libcnary/cnary.c)   # 独立测试程序，不进入生产静态库
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist PUBLIC ${PLIST}/include ${PLIST}/src ${PLIST}/libcnary ${PLIST}/libcnary/include)
target_compile_definitions(plist PRIVATE HAVE_STRNDUP HAVE_ASPRINTF HAVE_STPCOPY
    PACKAGE_VERSION="2.4.0" PACKAGE="libplist" PACKAGE_NAME="libplist" PACKAGE_STRING="libplist 2.4.0")

# ---------- UxPlay 协议栈 (静态) ----------
file(GLOB UXPLAY_SRC ${UXPLAY}/lib/*.c)
list(REMOVE_ITEM UXPLAY_SRC ${UXPLAY}/lib/dnssd.c)   # dnssd 用 NsdManager 替代

add_library(llhttp STATIC ${UXPLAY}/lib/llhttp/api.c ${UXPLAY}/lib/llhttp/http.c ${UXPLAY}/lib/llhttp/llhttp.c)
target_include_directories(llhttp PUBLIC ${UXPLAY}/lib/llhttp)

add_library(playfair STATIC
    ${UXPLAY}/lib/playfair/playfair.c
    ${UXPLAY}/lib/playfair/hand_garble.c
    ${UXPLAY}/lib/playfair/modified_md5.c
    ${UXPLAY}/lib/playfair/sap_hash.c
    ${UXPLAY}/lib/playfair/omg_hax.c)
target_include_directories(playfair PUBLIC ${UXPLAY}/lib/playfair)

add_library(airplay STATIC ${UXPLAY_SRC}
    ${CMAKE_SOURCE_DIR}/dnssd_android.c
    ${CMAKE_SOURCE_DIR}/video_renderer_android.c
    ${CMAKE_SOURCE_DIR}/audio_renderer_android.c)
target_include_directories(airplay PUBLIC ${UXPLAY}/lib ${UXPLAY}/renderers ${CMAKE_SOURCE_DIR}
    ${OPENSSL_DIR}/include)
target_compile_definitions(airplay PRIVATE
    STANDALONE __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS
    TARGET_POSIX _LINUX _REENTRANT _LARGEFILE64_SOURCE _FILE_OFFSET_BITS=64
    DNSSD_ANDROID PLIST_210)
target_link_libraries(airplay PUBLIC llhttp playfair plist log)

# ---------- JNI 桥 + 可执行入口 ----------
add_library(airplay-jni SHARED airplay_jni.c)
target_link_libraries(airplay-jni airplay
    ${OPENSSL_DIR}/lib/libssl.a
    ${OPENSSL_DIR}/lib/libcrypto.a
    log z)
