package com.openandroidintelligence.mobile.settings.advanced

import com.openandroidintelligence.mobile.updates.InstalledAppBuild
import com.openandroidintelligence.mobile.updates.appBuildChannelLabel

internal fun settingsBuildChannelLabel(build: InstalledAppBuild): String =
    if (build.channel in setOf("stable", "preview", "development")) {
        appBuildChannelLabel(build.channel, build.versionName)
    } else build.channel.ifBlank { "未知" }
