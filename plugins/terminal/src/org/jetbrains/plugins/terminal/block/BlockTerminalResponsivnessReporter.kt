// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.block

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalStartupMoment
import org.jetbrains.plugins.terminal.block.session.BlockTerminalSession
import org.jetbrains.plugins.terminal.block.session.ShellCommandListener
import org.jetbrains.plugins.terminal.fus.DurationType
import org.jetbrains.plugins.terminal.fus.TerminalUsageTriggerCollector
import org.jetbrains.plugins.terminal.util.ShellType
import java.time.Duration
import kotlin.time.toKotlinDuration

internal class BlockTerminalResponsivenessReporter(
  private val project: Project,
  private val startupMoment: TerminalStartupMoment,
  private val shellType: ShellType,
) : ShellCommandListener {

  // At this point, `TerminalCaretModel.onCommandRunningChanged(true)` should have been called already.
  // However, the terminal cursor is actually shown in 50ms after that, see `TerminalCaretModel.scheduleUpdate`.
  // Let's neglect these 50 ms for now. Maybe `TerminalCaretModel` could paint the cursor immediately on the first call?
  private val durationToCursorShownInInitializationBlock = startupMoment.elapsedNow()

  override fun initialized() {
    val durationToReadyPrompt = startupMoment.elapsedNow()
    val metrics = listOf(DurationType.FROM_STARTUP_TO_SHOWN_CURSOR to durationToCursorShownInInitializationBlock,
                         DurationType.FROM_STARTUP_TO_READY_PROMPT to durationToReadyPrompt)
    LOG.info("${shellType} new terminal started fully (" + metrics.joinToString { formatMessage(it.first, it.second) } + ")")
    metrics.forEach {
      TerminalUsageTriggerCollector.logBlockTerminalStepDuration(project, shellType, it.first, it.second.toKotlinDuration())
    }
  }
}

private fun formatMessage(durationType: DurationType, duration: Duration): String {
  return "${durationType.description}: ${duration.toMillis()} ms"
}

internal fun installResponsivenessReporter(project: Project, startupMoment: TerminalStartupMoment, session: BlockTerminalSession) {
  session.addCommandListener(BlockTerminalResponsivenessReporter(project, startupMoment, session.shellIntegration.shellType))
}
