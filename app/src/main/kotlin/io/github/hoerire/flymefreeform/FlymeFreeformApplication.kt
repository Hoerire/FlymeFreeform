package io.github.hoerire.flymefreeform

import android.app.Application
import io.github.hoerire.flymefreeform.apps.LauncherAppRepository
import io.github.hoerire.flymefreeform.apps.SidebarEntryRepository
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionRepository

class FlymeFreeformApplication : Application() {
    internal lateinit var frameworkConnectionRepository: FrameworkConnectionRepository
        private set
    internal lateinit var launcherAppRepository: LauncherAppRepository
        private set

    internal lateinit var sidebarEntryRepository: SidebarEntryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        frameworkConnectionRepository = FrameworkConnectionRepository(this).also { it.start() }
        sidebarEntryRepository = SidebarEntryRepository(this)
        launcherAppRepository = LauncherAppRepository(this).also { it.refresh() }
    }
}
