/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.sdl

import android.app.Activity
import android.view.Surface
import android.view.ViewGroup
import androidx.annotation.Keep
import androidx.annotation.MainThread
import com.movtery.zalithlauncher.setting.AllSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.libsdl.app.SDL
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLSurface
import org.lwjgl.glfw.CallbackBridge
import java.lang.ref.WeakReference

/**
 * Owns the SDL integration state shared by the launcher and game JVM.
 */
@Keep
object SdlBridge {
    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()

    private val _composeFocus = MutableStateFlow(0)
    val composeFocus = _composeFocus.asStateFlow()

    private var activityRef: WeakReference<Activity>? = null
    private var layoutRef: WeakReference<ViewGroup>? = null
    private var currentSurface: Surface? = null


    /** 当前注册 Surface 的来源 */
    private var currentSource: Any? = null

    /** 每次注册新 Surface 递增，供生命周期观测 */
    private var surfaceGeneration = 0L
    private var jniReady = false
    private var sdlInitialized = false

    @Volatile
    private var nativesLoaded = false

    @JvmStatic
    fun isNativesLoaded(): Boolean = nativesLoaded

    @JvmStatic
    fun markNativesLoaded() {
        nativesLoaded = true
    }

    /**
     * dlopen libSDL3 without ART System.loadLibrary / JNI_OnLoad (device SIGSEGV workaround).
     * Implemented in pojavexec (input_bridge_v3.c).
     */
    @JvmStatic
    private external fun nativeDlopenSdlOnly(): Boolean

    /**
     * Ensure libSDL3 is mapped. Prefer pure dlopen — never System.loadLibrary on this tree.
     */
    @JvmStatic
    @Synchronized
    fun ensureSdlLibraryMapped(): Boolean {
        if (nativesLoaded) return true
        return try {
            val ok = nativeDlopenSdlOnly()
            if (ok) {
                nativesLoaded = true
            }
            ok
        } catch (t: Throwable) {
            android.util.Log.e("SdlBridge", "ensureSdlLibraryMapped failed", t)
            false
        }
    }

    /**
     * Load libSDL3/libSDL2 on the Android (Dalvik) side with the app ClassLoader.
     * Prefer calling from Application.onCreate (pure ART) before the game JVM starts.
     */
    @JvmStatic
    @Synchronized
    fun preloadNatives(): Boolean {
        if (nativesLoaded) return true
        return try {
            android.util.Log.i("SdlBridge", "preloadNatives: resolving org.libsdl.app classes (ART main)")
            Class.forName("org.libsdl.app.SDLActivity")
            Class.forName("org.libsdl.app.SDL")
            Class.forName("org.libsdl.app.SDLSurface")
            Class.forName("org.libsdl.app.SDLAudioManager")
            Class.forName("org.libsdl.app.SDLControllerManager")
            try { Class.forName("org.libsdl.app.HIDDeviceManager") } catch (_: ClassNotFoundException) {}
            android.util.Log.i("SdlBridge", "preloadNatives: System.loadLibrary(SDL3) on ART")
            System.loadLibrary("SDL3")
            try { System.loadLibrary("SDL2") } catch (_: UnsatisfiedLinkError) {}
            // Register natives while still on pure ART
            try {
                if (!jniReady) {
                    SDL.setupJNI()
                    jniReady = true
                }
            } catch (t: Throwable) {
                android.util.Log.w("SdlBridge", "preload setupJNI: $t")
            }
            nativesLoaded = true
            android.util.Log.i("SdlBridge", "preloadNatives: OK (natives registered on ART)")
            true
        } catch (t: Throwable) {
            android.util.Log.e("SdlBridge", "preloadNatives failed", t)
            false
        }
    }

    @JvmStatic
    private external fun nativeRunDeferredSdlJniOnLoad(): Boolean

    /**
     * Run real SDL JNI_OnLoad outside ART LoadNativeLibrary (avoids SIGSEGV on some devices).
     */
    @JvmStatic
    @Synchronized
    fun runDeferredJniOnLoad(): Boolean {
        return try {
            nativeRunDeferredSdlJniOnLoad()
        } catch (t: Throwable) {
            android.util.Log.e("SdlBridge", "runDeferredJniOnLoad failed", t)
            false
        }
    }

    @JvmStatic
    @Synchronized
    fun setupJNI(): Boolean {
        if (jniReady) {
            return true
        }
        SDL.setupJNI()
        jniReady = true
        return true
    }

    @JvmStatic
    @Synchronized
    fun markSdlInitialized(): Boolean {
        if (sdlInitialized) return false
        sdlInitialized = true
        return true
    }

    @JvmStatic
    @Synchronized
    fun clearSdlInitialized() {
        sdlInitialized = false
    }

    @JvmStatic
    @Volatile
    var sdlEnabled: Boolean = false
        set(value) {
            field = value
            _enabled.value = value
        }

    /**
     * SDL 请求唤起输入法时，启动器侧是否响应
     */
    @JvmStatic
    fun getSdlImeAutoShowEnabled(): Boolean = AllSettings.sdlAutoShowIme.state

    @JvmStatic
    @MainThread
    fun prepareSurface(activity: Activity, surface: Surface, layout: ViewGroup?, source: Any? = null) {
        // Official does not load libSDL3 here — only notifyLauncher does.
        activityRef = WeakReference(activity)
        layoutRef = WeakReference(layout)
        currentSurface = surface
        currentSource = source
        surfaceGeneration++

        if (SDLActivity.getSDLSurface() == null) {
            SDL.initialize()
            SDL.setContext(activity)
            SDLActivity.externalInitialize(SDLSurface(activity), layout, surface)
        } else {
            SDLSurface.setNativeSurface(surface)
        }
    }

    @JvmStatic
    @MainThread
    fun registerSurface(activity: Activity, surface: Surface, layout: ViewGroup?) {
        activityRef = WeakReference(activity)
        layoutRef = WeakReference(layout)
        currentSurface = surface
    }

    @JvmStatic
    fun requestComposeFocus() {
        _composeFocus.update { it + 1 }
    }

    @JvmStatic
    @MainThread
    fun beginSurfaceDestroy(source: Any?, surface: Surface?): Boolean {
        return source != null && currentSource === source && surface != null && currentSurface === surface
    }

    @JvmStatic
    @MainThread
    fun unregisterSurface(surface: Surface?) {
        if (surface != null && currentSurface === surface) {
            currentSurface = null
            currentSource = null
        }
    }

    @JvmStatic
    @MainThread
    @Synchronized
    fun reset() {
        currentSurface = null
        currentSource = null
        surfaceGeneration = 0L
        activityRef = null
        layoutRef = null
        jniReady = false
        sdlInitialized = false
        sdlEnabled = false
        CallbackBridge.clearSdlBridgeState()
        SDLSurface.clearNativeSurface()
        SDL.initialize()
    }

    @JvmStatic
    fun initializeControllerSubsystems() {
        // Optional: full native port from official input_bridge when needed
    }

    /** True when MC is on the SDL window path (26.3+). Stub until native is wired. */
    @JvmStatic
    fun isSdlRenderActive(): Boolean = sdlEnabled

    @JvmStatic
    fun setNativeTextInputActive(active: Boolean): Boolean = false

    @JvmStatic
    fun isNativeTextInputActive(): Boolean = false
}