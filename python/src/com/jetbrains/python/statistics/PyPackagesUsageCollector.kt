package com.jetbrains.python.statistics

import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector

class PyPackagesUsageCollector : CounterUsagesCollector() {
  override fun getGroup() = GROUP

  @Suppress("CompanionObjectInExtension")
  companion object {
    private val GROUP = EventLogGroup("py.packages.features", 1)

    val installAllEvent = GROUP.registerEvent("install.all.missed.quickfix", EventFields.Count)

    @JvmField
    val installSingleEvent = GROUP.registerEvent("install.single.quickfix")
  }
}
