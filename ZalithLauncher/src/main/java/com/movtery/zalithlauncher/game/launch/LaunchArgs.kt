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

package com.movtery.zalithlauncher.game.launch

import java.util.Base64
import androidx.collection.ArrayMap
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.bridge.LoggerBridge
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.isAuthServerAccount
import com.movtery.zalithlauncher.game.account.isElyByAccount
import com.movtery.zalithlauncher.game.account.isLocalAccount
import com.movtery.zalithlauncher.game.account.offline.OfflineYggdrasilServer
import com.movtery.zalithlauncher.game.multirt.Runtime
import com.movtery.zalithlauncher.game.path.getAssetsHome
import com.movtery.zalithlauncher.game.path.getLibrariesHome
import com.movtery.zalithlauncher.game.plugin.natives.NativePluginManager
import com.movtery.zalithlauncher.game.version.download.artifactToPath
import com.movtery.zalithlauncher.game.version.download.filterLibrary
import com.movtery.zalithlauncher.game.version.download.getLibraryReplacement
import com.movtery.zalithlauncher.game.version.download.usesLwjglSdl
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionInfo
import com.movtery.zalithlauncher.game.version.installed.VersionInfoParser
import com.movtery.zalithlauncher.game.versioninfo.models.GameManifest
import com.movtery.zalithlauncher.path.LibPath
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.ui.screens.content.elements.QuickPlay
import com.movtery.zalithlauncher.utils.file.child
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.ServerAddress
import com.movtery.zalithlauncher.utils.string.insertJSONValueList
import com.movtery.zalithlauncher.utils.string.isEmptyOrBlank
import com.movtery.zalithlauncher.utils.string.isLowerTo
import com.movtery.zalithlauncher.utils.string.isNotEmptyOrBlank
import com.movtery.zalithlauncher.utils.string.toUnicodeEscaped
import java.io.File

private const val TAG = "LaunchArgs"

class LaunchArgs(
    private val runtimeLibraryPath: String,
    private val account: Account,
    private val offlineServer: OfflineYggdrasilServer,
    private val gameDirPath: File,
    private val version: Version,
    private val clientJar: File,
    private val gameManifest: GameManifest,
    private val runtime: Runtime,
    private val readAssetsFile: (path: String) -> String,
    private val getCacioJavaArgs: (isJava8: Boolean) -> List<String>
) {
    fun getAllArgs(): List<String> {
        val argsList: MutableList<String> = ArrayList()

        argsList.addAll(getJavaArgs())
        argsList.addAll(getMinecraftJVMArgs())
        argsList.addAll(NativePluginManager.getJVMEnv())
        argsList.add("-Dzstd.lib.path=${PathManager.DIR_NATIVE_LIB}/libzstd-jni-1.5.7-6-dhcompat.so")

        if (runtime.javaVersion > 8) {
            argsList.add("--add-exports")
            val pkg: String = gameManifest.mainClass.substring(0, gameManifest.mainClass.lastIndexOf("."))
            argsList.add("$pkg/$pkg=ALL-UNNAMED")
        }

        argsList.add(gameManifest.mainClass)
        argsList.addAll(getMinecraftClientArgs())

        version.getVersionInfo()?.let { info ->
            val quickPlay = version.quickPlaySingle
            if (quickPlay != null) {
                when (quickPlay) {
                    is QuickPlay.Save -> {
                        if (quickPlay.saveName.isEmptyOrBlank()) return@let

                        if (info.quickPlay.isQuickPlaySingleplayer) {
                            //将不受支持的字符转换为Unicode
                            val saveName = quickPlay.saveName.toUnicodeEscaped()
                            argsList.apply {
                                add("--quickPlaySingleplayer")
                                add(saveName)
                            }
                        } else {
                            val msg = "Quick Play for singleplayer is not supported and has been skipped."
                            LoggerBridge.append(msg)
                            Logger.warning(TAG, msg)
                        }
                    }
                    is QuickPlay.Server -> {
                        argsList.addQuickPlayServer(
                            address = quickPlay.serverAddress,
                            quickPlay = info.quickPlay
                        )
                    }
                }
            } else {
                version.getServerIp()?.let { address ->
                    argsList.addQuickPlayServer(
                        address = address,
                        quickPlay = info.quickPlay
                    )
                }
            }
        }

        return argsList
    }

    private fun MutableList<String>.addQuickPlayServer(
        address: String,
        quickPlay: VersionInfo.QuickPlay
    ) {
        runCatching {
            ServerAddress.parse(address)
        }.onFailure {
            val msg = "Unable to resolve the server address: $address. The automatic server join feature is unavailable."
            LoggerBridge.append(msg)
            Logger.warning(TAG, msg, it)
        }.getOrNull()?.let { parsed ->
            val args = if (quickPlay.isQuickPlayMultiplayer) {
                val port = if (parsed.port < 0) {
                    ServerAddress.DEFAULT_PORT
                } else {
                    parsed.port
                }

                listOf(
                    "--quickPlayMultiplayer",
                    "${parsed.getASCIIHost()}:$port"
                )
            } else {
                val port = parsed.port.takeIf { it >= 0 } ?: ServerAddress.DEFAULT_PORT
                listOf("--server", parsed.getASCIIHost(), "--port", port.toString())
            }

            addAll(args)
        }
    }

    private fun getLWJGL3ClassPath(): String {
        // MC 26.3+ (SDL) needs Android LWJGL 3.4.1 (SDL lives in merged-modules jar)
        val prefer341 = gameManifest.libraries.usesLwjglSdl()
        val dir341 = File(PathManager.DIR_COMPONENTS, "lwjgl/3.4.1")
        val dir333 = File(PathManager.DIR_COMPONENTS, "lwjgl3")
        val dir = if (prefer341 && dir341.isDirectory) dir341 else dir333
        return dir.listFiles { file -> file.name.endsWith(".jar") }
            // Skip LWJGL2 bridge only. lwjgl-vulkan.jar is REQUIRED on 3.4.1:
            // MC 26.x ClientBootstrap touches org.lwjgl.vulkan.* even for OpenGL.
            ?.filter { file -> file.name != "lwjgl-lwjglx.jar" }
            ?.sortedBy { file ->
                when {
                    file.name == "lwjgl.jar" -> 0
                    file.name.contains("merged-modules") -> 1
                    else -> 2
                }
            }
            ?.joinToString(":") { it.absolutePath }
            ?: ""
    }

    private fun getJavaArgs(): List<String> {
        val argsList: MutableList<String> = ArrayList()

        if (account.isLocalAccount()) {
            if (account.hasSkinFile || account.getCapeFile().exists()) {
                //该离线账号拥有本地皮肤，启用离线yggdrasil服务器
                offlineServer.start()
                offlineServer.addCharacter(account)
                offlineServer.getPort()?.let { port ->
                    val msg = "Using offline Yggdrasil server on port $port"
                    LoggerBridge.append(msg)
                    Logger.info(TAG, msg)
                    argsList.add("-javaagent:${LibPath.AUTHLIB_INJECTOR.absolutePath}=http://localhost:$port")
                    argsList.add("-Dauthlibinjector.side=client")
                } ?: run {
                    //无法获取端口号，说明服务器未成功启动
                    val msg = "Failed to start offline Yggdrasil server!"
                    LoggerBridge.append(msg)
                    Logger.warning(TAG, msg)
                    //本次启动将被忽略，为避免浪费性能，关停服务器
                    offlineServer.stop()
                }
            }
        } else if (account.isAuthServerAccount()) {
            if (account.otherBaseUrl!!.contains("auth.mc-user.com")) {
                argsList.add("-javaagent:${LibPath.NIDE_8_AUTH.absolutePath}=${account.otherBaseUrl!!.replace("https://auth.mc-user.com:233/", "")}")
                argsList.add("-Dnide8auth.client=true")
            } else if (account.isElyByAccount() && account.getCapeFile().exists() && account.hasSkinFile) {
                offlineServer.start()
                offlineServer.addCharacter(account)
                offlineServer.getPort()?.let { port ->
                    val msg = "Using offline Yggdrasil server with ely.by cape on port $port"
                    LoggerBridge.append(msg)
                    Logger.info(TAG, msg)
                    argsList.add("-javaagent:${LibPath.AUTHLIB_INJECTOR.absolutePath}=http://localhost:$port")
                    argsList.add("-Dauthlibinjector.side=client")
                } ?: run {
                    val msg = "Failed to start offline Yggdrasil server for ely.by cape!"
                    LoggerBridge.append(msg)
                    Logger.warning(TAG, msg)
                    offlineServer.stop()
                }
            } else {
                argsList.add("-javaagent:${LibPath.AUTHLIB_INJECTOR.absolutePath}=${account.otherBaseUrl}")
                argsList.add("-Dauthlibinjector.side=client")
            }
        }

        argsList.addAll(getCacioJavaArgs(runtime.javaVersion == 8))

        val configFilePath = version.getVersionPath().child("log4j2.xml")
        if (!configFilePath.exists()) {
            val is7 = (version.getVersionInfo()?.minecraftVersion ?: "0.0").isLowerTo("1.12")
            runCatching {
                val content = if (is7) {
                    readAssetsFile("components/log4j-1.7.xml")
                } else {
                    readAssetsFile("components/log4j-1.12.xml")
                }
                configFilePath.writeText(content)
            }.onFailure {
                Logger.warning(TAG, "Failed to write fallback Log4j configuration autonomously!", it)
            }
        }
        argsList.add("-Dlog4j.configurationFile=${configFilePath.absolutePath}")
        argsList.add("-Dminecraft.client.jar=${clientJar.absolutePath}")
        argsList.add("-Dminecraft.launcher.brand=${BuildKeys.LAUNCHER_NAME}")
        argsList.add("-Dminecraft.launcher.version=${BuildConfig.VERSION_NAME}")

        // Official 2.7.x uses real libvulkan.so. MC 26.x ClientBootstrap touches
        // org.lwjgl.vulkan.* even on the OpenGL path; the old "disabled" stub made
        // Plus diverge and contributed to a black screen while official played fine
        // with the same MobileGlues shader errors.
        argsList.add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
        // Prefer LWJGL 3.4.1 natives from components (same order as official log).
        val lwjglNatives = File(PathManager.DIR_COMPONENTS, "lwjgl/3.4.1/natives/arm64-v8a")
            .takeIf { it.isDirectory }
            ?: File(PathManager.DIR_COMPONENTS, "lwjgl3/natives")
                .takeIf { it.isDirectory }
        argsList.add(
            "-Dorg.lwjgl.librarypath=${lwjglNatives?.absolutePath ?: PathManager.DIR_NATIVE_LIB}"
        )
        argsList.add("-Dorg.lwjgl.sdl.libname=SDL3")

        return argsList
    }

    private fun getMinecraftJVMArgs(): Array<String> {
        val gameManifest1 = VersionInfoParser(version).build()

//        // Parse Forge 1.17+ additional JVM Arguments
//        if (versionInfo.inheritsFrom == null || versionInfo.arguments == null || versionInfo.arguments.jvm == null) {
//            return emptyArray()
//        }

        val varArgMap: MutableMap<String, String> = android.util.ArrayMap()
        val gameClassPath = generateLaunchClassPath(gameManifest)
        val lwjglClassPath = getLWJGL3ClassPath()
        // Stub VK first: UnsatisfiedLinkError instead of SIGSEGV in libpojavexec
        // Write jar if missing (component unpack may skip after upgrade)
        val vulkanStubFile = File(PathManager.DIR_COMPONENTS, "launcher/zalith-vulkan-stub.jar")
        if (!vulkanStubFile.isFile) {
            vulkanStubFile.parentFile?.mkdirs()
            vulkanStubFile.writeBytes(Base64.getDecoder().decode(
                "UEsDBBQACAgIAPiKTl0AAAAAAAAAAAAAAAAJAAQATUVUQS1JTkYv/soAAAMAUEsHCAAAAAACAAAAAAAAAFBLAwQUAAgICAD4ik5dAAAAAAAAAAAAAAAAFAAAAE1FVEEtSU5GL01BTklGRVNULk1G803My0xLLS7RDUstKs7Mz7NSMNQz4OVyLkpNLElN0XWqBAqY6xnoGVooaIQmleaVlGrycvFyAQBQSwcIpKR+PTgAAAA3AAAAUEsDBAoAAAgAAPeKTl0AAAAAAAAAAAAAAAAEAAAAb3JnL1BLAwQKAAAIAAD3ik5dAAAAAAAAAAAAAAAACgAAAG9yZy9sd2pnbC9QSwMECgAACAAA+IpOXQAAAAAAAAAAAAAAABEAAABvcmcvbHdqZ2wvdnVsa2FuL1BLAwQUAAgICAD4ik5dAAAAAAAAAAAAAAAAGQAAAG9yZy9sd2pnbC92dWxrYW4vVksuY2xhc3NdUE1Lw0AUnO1XYqxWW3tUFk+tB0OvWgUptUiLHqIBvW2SNW67bmSTVv+Wp4KC3v1R4iaIBXfhzdvH8GZ2vr7fPgCcoO2ghLKFSh1V1Ai2pmzBXMlU7F4FUx5mBLW+UCI7JSh3ur4Fm2BvRbpRKctEei94NBFqNtQ60TYcgsEdkyJ7OKL+XM6YoiKlkUhZIHlEE0XPVKQTEdEOWxhIqRTBU2LW8hceUu9i5A1HfteBhXpubIOg3ZmsVL1MCxUf53YaBK1Ex658nsbSXRRirj8mqAySiBM0jC1+OX8MuL7O1Qnsfih/f+R4yVyH/Fzkc8sfH+YS6GHThJKfkrkmFphczGvXIDFYPViCvJqGYNvUWjF0YKP5R91HuVjgvMO6ba4tsf75j181tVVI7PwAUEsHCBXGyiQtAQAAjQEAAFBLAwQUAAgICAD3ik5dAAAAAAAAAAAAAAAAGAAAAG9yZy9sd2pnbC92dWxrYW4vVksuamF2YVWPuw7CMAxF93zFFVO78AEwMSCEYEN0YHObtJhaSZWkLRLqv5OCxOMMtq5fuu6oaqkxcL5ZynhrZDn00pJdq64vhSvUbElQCYWA4oCHQiJEiqn3FjNcI4u+N/lPbSZevRthzYizDWkn1Gz0kW279d757G92ZnEh4XhdoXi5AAdoDlSK0XAWG6u9Y42MhpQChMvO3Wgwd1PhtN+dtrsiX/xdzdcfOalv7DwPFE16KUueJzWpJ1BLBwhQmy8rtAAAAAoBAABQSwECFAAUAAgICAD4ik5dAAAAAAIAAAAAAAAACQAEAAAAAAAAAAAAAAAAAAAATUVUQS1JTkYv/soAAFBLAQIUABQACAgIAPiKTl2kpH49OAAAADcAAAAUAAAAAAAAAAAAAAAAAD0AAABNRVRBLUlORi9NQU5JRkVTVC5NRlBLAQIKAAoAAAgAAPeKTl0AAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAALcAAABvcmcvUEsBAgoACgAACAAA94pOXQAAAAAAAAAAAAAAAAoAAAAAAAAAAAAAAAAA2QAAAG9yZy9sd2pnbC9QSwECCgAKAAAIAAD4ik5dAAAAAAAAAAAAAAAAEQAAAAAAAAAAAAAAAAABAQAAb3JnL2x3amdsL3Z1bGthbi9QSwECFAAUAAgICAD4ik5dFcbKJC0BAACNAQAAGQAAAAAAAAAAAAAAAAAwAQAAb3JnL2x3amdsL3Z1bGthbi9WSy5jbGFzc1BLAQIUABQACAgIAPeKTl1Qmy8rtAAAAAoBAAAYAAAAAAAAAAAAAAAAAKQCAABvcmcvbHdqZ2wvdnVsa2FuL1ZLLmphdmFQSwUGAAAAAAcABwCzAQAAngMAAAAA"
            ))
        }
        val vulkanStub = vulkanStubFile.absolutePath
        // LWJGL 3.4.1 (MC 26.x / SDL): use real lwjgl-vulkan.jar from components.
        // Stub only for older paths — it shadows VK.create() / VkPhysicalDeviceProperties2.
        val launchClassPath = if (gameManifest.libraries.usesLwjglSdl()) {
            "$lwjglClassPath:$gameClassPath"
        } else {
            "$vulkanStub:$lwjglClassPath:$gameClassPath"
        }
        var hasClasspath = false //是否已经在jvm参数中包含 ${classpath} 配置

        varArgMap["classpath_separator"] = ":"
        varArgMap["library_directory"] = getLibrariesHome()
        varArgMap["version_name"] = gameManifest1.id
        varArgMap["natives_directory"] = runtimeLibraryPath
        setLauncherInfo(varArgMap)

        fun Any.processJvmArg(): String? = (this as? String)?.let { argument ->
            if (argument.startsWith("-Djava.library.path=")) {
                // 26.2+ Mojang uses concrete path; redirect to runtime natives + APK jniLibs
                return@let "-Djava.library.path=" + runtimeLibraryPath + ":" + PathManager.DIR_NATIVE_LIB
            }
            when {
                argument.startsWith("-DignoreList=") -> {
                    "$argument,${version.getVersionName()}.jar"
                }
                argument.contains("-Dio.netty.native.workdir") ||
                argument.contains("-Djna.tmpdir") ||
                argument.contains("-Dorg.lwjgl.system.SharedLibraryExtractPath") -> {
                    //使用一个可读的目录
                    argument.replace($$"${natives_directory}", PathManager.DIR_CACHE.absolutePath)
                }
                argument == $$"${classpath}" -> {
                    hasClasspath = true
                    launchClassPath
                }
                else -> argument
            }
        }

        val jvmArgs = gameManifest1.arguments?.jvm
            ?.mapNotNull { it.processJvmArg() }
            ?.toTypedArray()
            ?: emptyArray()

        val replacedArgs = insertJSONValueList(jvmArgs, varArgMap)
        return if (hasClasspath) {
            replacedArgs
        } else {
            //不包含 ${classpath} 配置，则需要手动添加
            replacedArgs + arrayOf("-cp", launchClassPath)
        }
    }

    /**
     * [Modified from PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher/blob/a6f3fc0/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java#L572-L592)
     */
    private fun generateLaunchClassPath(gameManifest: GameManifest): String {
        val classpathList = mutableListOf<String>()
        val classpath: Array<String> = generateLibClasspath(gameManifest)

        for (jarFile in classpath) {
            val jarFileObj = File(jarFile)
            if (!jarFileObj.exists()) {
                Logger.debug(TAG, "Ignored non-exists file: $jarFile")
                continue
            }
            classpathList.add(jarFile)
        }
        if (clientJar.exists()) {
            classpathList.add(clientJar.absolutePath)
        }

        return classpathList.joinToString(":")
    }

    /**
     * [Modified from PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher/blob/a6f3fc0/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java#L871-L882)
     */
    private fun generateLibClasspath(gameManifest: GameManifest): Array<String> {
        val libSortFix = LibSortFix(version.getVersionInfo())
        val libs = LinkedHashMap<GameManifest.Library, String>()
        val usesLwjglSdl = gameManifest.libraries.usesLwjglSdl()

        for (libItem in gameManifest.libraries) {
            if (!(GameManifest.Rule.checkRules(libItem.rules) && !libItem.isNative)) continue
            val path = libItem.progressLibrary(allowLwjglSdlClasses = usesLwjglSdl, allowVulkan = false) ?: continue
            with(libSortFix) {
                libs.insertLib(libItem, getLibrariesHome() + "/" + path)
            }
        }

        return libs.values.toTypedArray<String>()
    }


    /**
     * @return 库相对路径
     */
    private fun GameManifest.Library.progressLibrary(
        allowLwjglSdlClasses: Boolean = false,
        allowVulkan: Boolean = false
    ): String? {
        if (filterLibrary(allowLwjglSdlClasses, allowVulkan)) return null

        var path = artifactToPath(this)

        val versionSegment = name.split(":").getOrNull(2) ?: return path
        val versionParts = versionSegment.split(".")

        getLibraryReplacement(name, versionParts)?.let { replacement ->
            Logger.debug(TAG, "Library ${this.name} has been changed to version ${replacement.newName.split(":").last()}")
            path = replacement.newPath
        }

        return path
    }

    private fun getMinecraftClientArgs(): Array<String> {
        val varArgMap: MutableMap<String, String> = ArrayMap()
        varArgMap["auth_session"] = account.accessToken
        varArgMap["auth_access_token"] = account.accessToken
        varArgMap["auth_player_name"] = account.username
        varArgMap["auth_uuid"] = account.profileId.replace("-", "")
        varArgMap["auth_xuid"] = account.xUid ?: ""
        varArgMap["assets_root"] = getAssetsHome()
        varArgMap["assets_index_name"] = gameManifest.assetIndex.id
        varArgMap["game_assets"] = getAssetsHome()
        varArgMap["game_directory"] = gameDirPath.absolutePath
        varArgMap["user_properties"] = "{}"
        varArgMap["user_type"] = "msa"
        varArgMap["version_name"] = gameManifest.id

        setLauncherInfo(varArgMap)

        val minecraftArgs: MutableList<String> = ArrayList()
        gameManifest.arguments?.apply {
            // Support Minecraft 1.13+
            game.forEach { if (it is String) minecraftArgs.add(it) }
        }

        return insertJSONValueList(
            splitAndFilterEmpty(
                gameManifest.minecraftArguments ?:
                minecraftArgs.toTypedArray().joinToString(" ")
            ), varArgMap
        )
    }

    private fun setLauncherInfo(verArgMap: MutableMap<String, String>) {
        verArgMap["launcher_name"] = BuildKeys.LAUNCHER_NAME
        verArgMap["launcher_version"] = BuildConfig.VERSION_NAME
        verArgMap["version_type"] = version.getCustomInfo()
            .takeIf { it.isNotEmptyOrBlank() }
            ?: gameManifest.type
    }

    private fun splitAndFilterEmpty(arg: String): Array<String> {
        val list: MutableList<String> = ArrayList()
        arg.split(" ").forEach {
            if (it.isNotEmpty()) list.add(it)
        }
        return list.toTypedArray()
    }
}