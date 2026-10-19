//
// Created by maks on 06.01.2025.
// Extended: GL sync symbol fallback for MobileGlues / MC 26.3
//

#include <android/api-level.h>
#include <android/log.h>
#include <jni.h>

#include <environ/environ.h>

#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

extern void* maybe_load_vulkan();

#define LOG_TAG "LwjglLinkerHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/* ---- GL sync fallbacks (GLES3 has these; some MobileGlues builds omit desktop exports) ---- */
typedef void* (*egl_gpa_t)(const char*);

static egl_gpa_t get_egl_gpa(void) {
    static egl_gpa_t eglGPA = NULL;
    static int tried = 0;
    if (tried) return eglGPA;
    tried = 1;
    void* egl = dlopen("libEGL.so", RTLD_NOLOAD | RTLD_NOW);
    if (!egl) egl = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
    if (egl) eglGPA = (egl_gpa_t) dlsym(egl, "eglGetProcAddress");
    return eglGPA;
}

static void* gles_dlsym(const char* name) {
    static void* gles = NULL;
    static int tried = 0;
    if (!tried) {
        tried = 1;
        gles = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
        if (!gles) gles = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
    }
    return gles ? dlsym(gles, name) : NULL;
}

static void* resolve_gl_proc(const char* name) {
    egl_gpa_t gpa = get_egl_gpa();
    void* p = gpa ? (void*) gpa(name) : NULL;
    if (p) return p;
    return gles_dlsym(name);
}

/* No-op / safe stubs if driver truly lacks the symbol */
static void stub_glDeleteSync(void* sync) {
    void (*real)(void*) = (void (*)(void*)) resolve_gl_proc("glDeleteSync");
    if (real) real(sync);
}

static void* stub_glFenceSync(unsigned int condition, unsigned int flags) {
    void* (*real)(unsigned int, unsigned int) =
        (void* (*)(unsigned int, unsigned int)) resolve_gl_proc("glFenceSync");
    return real ? real(condition, flags) : NULL;
}

static int stub_glClientWaitSync(void* sync, unsigned int flags, uint64_t timeout) {
    int (*real)(void*, unsigned int, uint64_t) =
        (int (*)(void*, unsigned int, uint64_t)) resolve_gl_proc("glClientWaitSync");
    /* GL_TIMEOUT_EXPIRED = 0x911B, GL_ALREADY_SIGNALED = 0x911A, GL_CONDITION_SATISFIED = 0x911C */
    return real ? real(sync, flags, timeout) : 0x911A;
}

static void stub_glWaitSync(void* sync, unsigned int flags, uint64_t timeout) {
    void (*real)(void*, unsigned int, uint64_t) =
        (void (*)(void*, unsigned int, uint64_t)) resolve_gl_proc("glWaitSync");
    if (real) real(sync, flags, timeout);
}

static unsigned char stub_glIsSync(void* sync) {
    unsigned char (*real)(void*) = (unsigned char (*)(void*)) resolve_gl_proc("glIsSync");
    return real ? real(sync) : 0;
}

static void* gl_sync_fallback(const char* name) {
    if (!name) return NULL;
    if (strcmp(name, "glDeleteSync") == 0) {
        void* p = resolve_gl_proc(name);
        return p ? p : (void*) &stub_glDeleteSync;
    }
    if (strcmp(name, "glFenceSync") == 0) {
        void* p = resolve_gl_proc(name);
        return p ? p : (void*) &stub_glFenceSync;
    }
    if (strcmp(name, "glClientWaitSync") == 0) {
        void* p = resolve_gl_proc(name);
        return p ? p : (void*) &stub_glClientWaitSync;
    }
    if (strcmp(name, "glWaitSync") == 0) {
        void* p = resolve_gl_proc(name);
        return p ? p : (void*) &stub_glWaitSync;
    }
    if (strcmp(name, "glIsSync") == 0) {
        void* p = resolve_gl_proc(name);
        return p ? p : (void*) &stub_glIsSync;
    }
    return NULL;
}

/**
 * ndlopen — vulkan override + Android 10 namespace fix
 */
static jlong ndlopen_bugfix(__attribute__((unused)) JNIEnv *env,
                            __attribute__((unused)) jclass class,
                            jlong filename_ptr,
                            jint jmode) {
    const char* filename = (const char*) filename_ptr;

    if (filename && strstr(filename, "libvulkan.so") == filename) {
        LOGI("replacing load for libvulkan.so with custom driver");
        return (jlong) maybe_load_vulkan();
    }

    int mode = (int) jmode;
    return (jlong) dlopen(filename, mode);
}

/**
 * ndlsym — if MobileGlues/libGL returns NULL for GL sync entry points,
 * supply GLES3 / eglGetProcAddress / safe stubs so MC 26.3 RenderPearl can run.
 */
static jlong ndlsym_hook(__attribute__((unused)) JNIEnv *env,
                        __attribute__((unused)) jclass class,
                        jlong handle,
                        jlong name_ptr) {
    const char* name = (const char*) name_ptr;
    void* sym = NULL;
    if (handle != 0 && name != NULL) {
        sym = dlsym((void*) handle, name);
    }
    if (sym == NULL && name != NULL) {
        void* fb = gl_sync_fallback(name);
        if (fb != NULL) {
            LOGW("ndlsym fallback for %s -> %p", name, fb);
            sym = fb;
        }
    }
    return (jlong) sym;
}

void installLwjglDlopenHook() {
    LOGI("Installing LWJGL dlopen/dlsym hooks");
    JNIEnv* env = pojav_environ->runtimeJNIEnvPtr_JRE;
    if (env == NULL) {
        LOGW("no JRE JNIEnv — skip hook install");
        return;
    }
    jclass dynamicLinkLoader = (*env)->FindClass(env, "org/lwjgl/system/linux/DynamicLinkLoader");
    if (dynamicLinkLoader == NULL) {
        LOGW("Failed to find DynamicLinkLoader");
        (*env)->ExceptionClear(env);
        return;
    }
    JNINativeMethod methods[] = {
            {"ndlopen", "(JI)J", (void*) &ndlopen_bugfix},
            {"ndlsym",  "(JJ)J", (void*) &ndlsym_hook},
    };
    if ((*env)->RegisterNatives(env, dynamicLinkLoader, methods, 2) != 0) {
        LOGW("Failed to register ndlopen/ndlsym hooks");
        (*env)->ExceptionClear(env);
    } else {
        LOGI("ndlopen + ndlsym hooks registered");
    }
}
