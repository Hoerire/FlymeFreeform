package io.github.hoerire.flymefreeform

import android.app.Application
import io.github.hoerire.flymefreeform.apps.LauncherAppRepository
import io.github.hoerire.flymefreeform.apps.SidebarEntryRepository
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionRepository
import io.github.hoerire.flymefreeform.update.UpdateChecker

class FlymeFreeformApplication : Application() {
    internal lateinit var frameworkConnectionRepository: FrameworkConnectionRepository
        private set
    internal lateinit var launcherAppRepository: LauncherAppRepository
        private set

    internal lateinit var sidebarEntryRepository: SidebarEntryRepository
        private set

    internal lateinit var updateChecker: UpdateChecker
        private set

    override fun onCreate() {
        super.onCreate()
        frameworkConnectionRepository = FrameworkConnectionRepository(this).also { it.start() }
        sidebarEntryRepository = SidebarEntryRepository(this)
        launcherAppRepository = LauncherAppRepository(this).also { it.refresh() }
        // 每次打开应用检查一次；不做后台任务，命中六小时缓存时不联网。
        updateChecker = UpdateChecker(this).also { it.checkOnLaunch() }
    }
}
