// SDL JNI_OnLoad isolation for the embedded game JVM.
// On some devices (e.g. ZTE Unisoc API 33) calling the real SDL JNI_OnLoad
// *inside* ART LoadNativeLibrary (System.loadLibrary) SIGSEGVs in FindClass.
// Strategy:
//   1) During LoadNativeLibrary, only capture the real OnLoad pointer and return
//      JNI_VERSION_1_4 (do NOT invoke original yet).
//   2) After loadLibrary returns, call run_deferred_sdl_jni_onload() from ART
//      (outside LoadNativeLibrary) to perform RegisterNatives safely.

#include <dlfcn.h>
#include <jni.h>
#include <bytehook.h>
#include <stdlib.h>
#include <string.h>

#include "environ/environ.h"
#include "logger/logger.h"
#include "sdl_hook.h"
#include "utils.h"

typedef void *(*dlsym_func_t)(void *handle, const char *symbol);
typedef jint (*jni_on_load_func_t)(JavaVM *vm, void *reserved);

typedef bytehook_stub_t (*bytehook_hook_all_fn)(const char *callee_path_name, const char *symbol,
                                                  void *new_func, bytehook_hooked_t hooked, void *hooked_arg);

static void *sdlHandle;
static jni_on_load_func_t originalSdlJniOnLoad;
static volatile int sSdlJniOnLoadDone = 0;

static jint isolatedSdlJniOnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    /* Capture only — never run real OnLoad while still inside LoadNativeLibrary. */
    if (originalSdlJniOnLoad != NULL && pojav_environ != NULL
            && pojav_environ->dalvikJavaVMPtr == vm) {
        LOG_TO_I("SDL_Hook: deferring real JNI_OnLoad (vm=%p) until after loadLibrary", (void *) vm);
    } else {
        LOG_TO_I("SDL_Hook: skip JNI_OnLoad for non-Dalvik vm=%p", (void *) vm);
    }
    return JNI_VERSION_1_4;
}

/* Call after System.loadLibrary("SDL3") has returned, on ART. */
int run_deferred_sdl_jni_onload(void) {
    if (sSdlJniOnLoadDone) {
        LOG_TO_I("SDL_Hook: deferred JNI_OnLoad already done");
        return 1;
    }
    if (originalSdlJniOnLoad == NULL) {
        LOG_TO_E("SDL_Hook: deferred JNI_OnLoad: original pointer is NULL");
        return 0;
    }
    if (pojav_environ == NULL || pojav_environ->dalvikJavaVMPtr == NULL) {
        LOG_TO_E("SDL_Hook: deferred JNI_OnLoad: dalvik JavaVM is NULL");
        return 0;
    }
    LOG_TO_I("SDL_Hook: running deferred real JNI_OnLoad on Dalvik vm=%p",
             (void *) pojav_environ->dalvikJavaVMPtr);
    jint ver = originalSdlJniOnLoad(pojav_environ->dalvikJavaVMPtr, NULL);
    sSdlJniOnLoadDone = 1;
    LOG_TO_I("SDL_Hook: deferred JNI_OnLoad finished, version=0x%x", (unsigned) ver);
    return 1;
}

static void *customDlsym(void *handle, const char *symbol) {
    void *result = BYTEHOOK_CALL_PREV(customDlsym, dlsym_func_t, handle, symbol);
    BYTEHOOK_POP_STACK();
    if (sdlHandle == NULL) {
        void *noload = dlopen("libSDL3.so", RTLD_NOLOAD);
        sdlHandle = noload ? noload : dlopen("libSDL3.so", RTLD_NOW);
    }
    if (sdlHandle != NULL && handle == sdlHandle && symbol != NULL && strcmp(symbol, "JNI_OnLoad") == 0) {
        originalSdlJniOnLoad = (jni_on_load_func_t) result;
        result = (void *) isolatedSdlJniOnLoad;
        LOG_TO_I("SDL_Hook: replaced libSDL3 JNI_OnLoad with deferred wrapper");
    }
    if (result != NULL && symbol != NULL) {
        void *proxy = sdlDlsymProxy(symbol, result);
        if (proxy != NULL) {
            LOG_TO_I("SDL_Hook: dlsym proxy for %s", symbol);
            result = proxy;
        }
    }
    return result;
}

void create_sdl_dlopen_hooks(bytehook_hook_all_fn hookAll) {
    if (hookAll == NULL) return;
    hookAll(NULL, "dlsym", (void *) customDlsym, NULL, NULL);
}
