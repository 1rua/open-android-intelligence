package com.openandroidintelligence.tailnet.core

import android.app.Application
import java.io.File

/**
 * 测试进程最早执行点：确保在任何 native(Go) 调用前把 HOME/XDG_CONFIG_HOME 指到
 * 本测试应用可写目录。Android 进程没有可写 HOME，tsnet 启动要建 $HOME/.config
 * （“mkdir /.config: read-only file system”）会瞬时失败。
 *
 * 注意：Java `Os.setenv` 必须在 Go 运行时 init（首次加载 libgojni）之前执行，
 * 否则 Go 看不到（它只读启动时快照的 environ）。Application.onCreate 是最早保证点。
 */
class P0tGoRuntimeEnvApplication : Application() {
    override fun onCreate() {
        runCatching { android.system.Os.setenv("HOME", filesDir.absolutePath, true) }
        runCatching {
            android.system.Os.setenv(
                "XDG_CONFIG_HOME",
                File(filesDir, ".config").absolutePath,
                true,
            )
        }
        super.onCreate()
    }
}
