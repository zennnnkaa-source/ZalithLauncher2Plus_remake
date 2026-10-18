// SDL JNI_OnLoad isolation for the embedded game JVM.
// On some devices (ZTE Unisoc API 33) calling real SDL JNI_OnLoad *inside*
// ART LoadNativeLibrary SIGSEGVs in FindClass.
//
// Strategy (aligned with official isolation + device fix):
//   1) Intercept dlsym("JNI_OnLoad") for libSDL3; replace with stub that only
//      returns JNI_VERSION_1_4 (does NOT run real OnLoad during LoadNativeLibrary).
//   2) Save real OnLoad into pojavexec via sdl_set_original_jni_onload().
//   3) After System.loadLibrary returns, Java calls SdlBridge.runDeferredJniOnLoad()
//      which runs the real OnLoad outside LoadNativeLibrary.

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

static jint isolatedSdlJniOnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    if (pojav_environ != NULL && pojav_environ->dalvikJavaVMPtr == vm) {
        LOG_TO_I("SDL_Hook: deferring real JNI_OnLoad (vm=%p) until after loadLibrary", (void *) vm);
    } else {
        LOG_TO_I("SDL_Hook: skip JNI_OnLoad for non-Dalvik vm=%p", (void *) vm);
    }
    return JNI_VERSION_1_4;
}

static int is_sdl3_jni_onload(void *handle, const char *symbol, void *result) {
    if (symbol == NULL || strcmp(symbol, "JNI_OnLoad") != 0 || result == NULL) {
        return 0;
    }
    /* Official path: handle matches our libSDL3 mapping */
    if (sdlHandle != NULL && handle == sdlHandle) {
        return 1;
    }
    /* Fallback: dladdr — ART LoadNativeLibrary may pass a different handle */
    Dl_info info;
    if (dladdr(result, &info) != 0 && info.dli_fname != NULL) {
        if (strstr(info.dli_fname, "libSDL3.so") != NULL) {
            return 1;
        }
    }
    return 0;
}

static void *customDlsym(void *handle, const char *symbol) {
    void *result = BYTEHOOK_CALL_PREV(customDlsym, dlsym_func_t, handle, symbol);
    BYTEHOOK_POP_STACK();

    if (sdlHandle == NULL) {
        /* Prefer already-mapped System.loadLibrary copy (official uses RTLD_LOCAL|NOW) */
        void *noload = dlopen("libSDL3.so", RTLD_NOLOAD);
        if (noload != NULL) {
            sdlHandle = noload;
        } else {
            sdlHandle = dlopen("libSDL3.so", RTLD_LOCAL | RTLD_NOW);
        }
    }

    if (is_sdl3_jni_onload(handle, symbol, result)) {
        sdl_set_original_jni_onload(result);
        LOG_TO_I("SDL_Hook: captured libSDL3 JNI_OnLoad, installing deferred wrapper");
        result = (void *) isolatedSdlJniOnLoad;
    }

    /* Official: LWJGL bindings go through dlsym proxies */
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
