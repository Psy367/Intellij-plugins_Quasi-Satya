// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.packaging.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.modules
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SideBorder
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.jetbrains.python.PyBundle.message
import com.jetbrains.python.icons.PythonIcons
import com.jetbrains.python.packaging.common.PythonLocalPackageSpecification
import com.jetbrains.python.packaging.common.PythonVcsPackageSpecification
import com.jetbrains.python.packaging.toolwindow.details.PyPackageDescriptionController
import com.jetbrains.python.packaging.toolwindow.model.DisplayablePackage
import com.jetbrains.python.packaging.toolwindow.model.InstalledPackage
import com.jetbrains.python.packaging.toolwindow.model.PyPackagesViewData
import com.jetbrains.python.packaging.toolwindow.packages.PyPackageSearchTextField
import com.jetbrains.python.packaging.toolwindow.packages.PyPackagesListController
import com.jetbrains.python.packaging.toolwindow.ui.PyPackagesUiComponents
import com.jetbrains.python.packaging.utils.PyPackageCoroutine
import com.jetbrains.python.sdk.pythonSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.event.ListSelectionListener

class PyPackagingToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(false, true), Disposable {
  private val descriptionController = PyPackageDescriptionController(project).also {
    Disposer.register(this, it)
  }

  private val packageListController = PyPackagesListController(project, controller = this).also {
    Disposer.register(this, it)
  }


  internal val packagingScope = PyPackageCoroutine.getIoScope(project)

  private val searchTextField: SearchTextField = PyPackageSearchTextField(project)

  private val noPackagePanel = JBPanelWithEmptyText().apply { emptyText.text = message("python.toolwindow.packages.description.panel.placeholder") }

  // layout
  private var mainPanel: JPanel? = null
  private var splitter: OnePixelSplitter? = null
  private var leftPanel: JComponent
  private val rightPanel: JComponent = descriptionController.wrappedComponent

  internal var contentVisible: Boolean
    get() = mainPanel!!.isVisible
    set(value) {
      mainPanel!!.isVisible = value
    }


  private val fromVcsText: String
    get() = message("python.toolwindow.packages.add.package.from.vcs")
  private val fromDiscText: String
    get() = message("python.toolwindow.packages.add.package.from.disc")


  init {
    val service = project.service<PyPackagingToolWindowService>()
    Disposer.register(service, this)
    withEmptyText(message("python.toolwindow.packages.no.interpreter.text"))

    leftPanel = createLeftPanel(service)



    initOrientation(service, true)
    trackOrientation(service)
  }


  override fun uiDataSnapshot(sink: DataSink) {
    sink[PyPackagesUiComponents.SELECTED_PACKAGE_DATA_CONTEXT] = descriptionController.selectedPackage.get()
    sink[PyPackagesUiComponents.SELECTED_PACKAGES_DATA_CONTEXT] = this.packageListController.getSelectedPackages()
    super.uiDataSnapshot(sink)
  }

  fun getSelectedPackage(): DisplayablePackage? = descriptionController.selectedPackage.get()

  private fun initOrientation(service: PyPackagingToolWindowService, horizontal: Boolean) {
    val second = if (splitter?.secondComponent == rightPanel) rightPanel else noPackagePanel
    val proportionKey = if (horizontal) HORIZONTAL_SPLITTER_KEY else VERTICAL_SPLITTER_KEY
    splitter = OnePixelSplitter(!horizontal, proportionKey, 0.3f).apply {
      firstComponent = leftPanel
      secondComponent = second
    }

    val actionGroup = DefaultActionGroup()
    actionGroup.add(DumbAwareAction.create(message("python.toolwindow.packages.reload.repositories.action"), AllIcons.Actions.Refresh) {
      service.reloadPackages()
    })
    actionGroup.add(DumbAwareAction.create(message("python.toolwindow.packages.manage.repositories.action"), AllIcons.General.GearPlain) {
      service.manageRepositories()
    })
    val actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actionGroup, true)
    actionToolbar.targetComponent = this


    val installFromLocationLink = DropDownLink(message("python.toolwindow.packages.add.package.action"),
                                               listOf(fromVcsText, fromDiscText)) {
      val specification = when (it) {
        fromDiscText -> showInstallFromDiscDialog(service)
        fromVcsText -> showInstallFromVcsDialog(service)
        else -> throw IllegalStateException("Unknown operation")
      }
      if (specification != null) {
        packagingScope.launch {
          service.installPackage(specification)
        }
      }
    }

    mainPanel = PyPackagesUiComponents.borderPanel {
      val topToolbar = PyPackagesUiComponents.boxPanel {
        border = SideBorder(NamedColorUtil.getBoundsColor(), SideBorder.BOTTOM)
        preferredSize = Dimension(preferredSize.width, 30)
        minimumSize = Dimension(minimumSize.width, 30)
        maximumSize = Dimension(maximumSize.width, 30)
        add(searchTextField)
        actionToolbar.component.maximumSize = Dimension(70, actionToolbar.component.maximumSize.height)
        add(actionToolbar.component)
        add(installFromLocationLink)
      }
      add(topToolbar, BorderLayout.NORTH)
      add(splitter!!, BorderLayout.CENTER)
    }
    setContent(mainPanel!!)
  }

  private fun createLeftPanel(service: PyPackagingToolWindowService): JComponent {
    if (project.modules.size == 1) return packageListController.component

    val left = JPanel(BorderLayout()).apply {
      border = BorderFactory.createEmptyBorder()
    }

    val modulePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
      border = SideBorder(NamedColorUtil.getBoundsColor(), SideBorder.RIGHT)
      maximumSize = Dimension(80, maximumSize.height)
      minimumSize = Dimension(50, minimumSize.height)
    }

    val moduleList = JBList(ModuleManager.getInstance(project).modules.toList().sortedBy { it.name }).apply {
      selectionMode = ListSelectionModel.SINGLE_SELECTION
      border = JBUI.Borders.empty()

      val itemRenderer = JBLabel("", PythonIcons.Python.PythonClosed, JLabel.LEFT).apply {
        border = JBUI.Borders.empty(0, 10)
      }
      cellRenderer = ListCellRenderer { _, value, _, _, _ -> itemRenderer.text = value.name; itemRenderer }

      addListSelectionListener(ListSelectionListener { e ->
        if (e.valueIsAdjusting) return@ListSelectionListener
        val selectedModule = this@apply.selectedValue
        val sdk = selectedModule.pythonSdk ?: return@ListSelectionListener
        packagingScope.launch {
          service.initForSdk(sdk)
        }
      })
    }

    val fileListener = object : FileEditorManagerListener {
      override fun selectionChanged(event: FileEditorManagerEvent) {
        if (project.modules.size > 1) {
          val newFile = event.newFile ?: return
          val module = ModuleUtilCore.findModuleForFile(newFile, project)
          packagingScope.launch {
            val index = (moduleList.model as DefaultListModel<Module>).indexOf(module)
            moduleList.selectionModel.setSelectionInterval(index, index)
          }
        }
      }
    }
    service.project.messageBus
      .connect(service)
      .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, fileListener)

    modulePanel.add(moduleList)
    left.add(ScrollPaneFactory.createScrollPane(modulePanel, true), BorderLayout.WEST)
    left.add(packageListController.component, BorderLayout.CENTER)

    return left
  }

  private fun showInstallFromVcsDialog(service: PyPackagingToolWindowService): PythonVcsPackageSpecification? {
    var editable = false
    var link = ""
    val systems = listOf(message("python.toolwindow.packages.add.package.vcs.git"),
                         message("python.toolwindow.packages.add.package.vcs.svn"),
                         message("python.toolwindow.packages.add.package.vcs.hg"),
                         message("python.toolwindow.packages.add.package.vcs.bzr"))
    var vcs = systems.first()

    val panel = panel {
      row {
        comboBox(systems)
          .bindItem({ vcs }, { vcs = it!! })
        textField()
          .columns(COLUMNS_MEDIUM)
          .bindText({ link }, { link = it })
          .align(AlignX.FILL)
      }
      row {
        checkBox(message("python.toolwindow.packages.add.package.as.editable"))
          .bindSelected({ editable }, { editable = it })
      }
    }

    val shouldInstall = dialog(message("python.toolwindow.packages.add.package.dialog.title"), panel, project = service.project, resizable = true).showAndGet()
    if (shouldInstall) {
      val prefix = when (vcs) {
        message("python.toolwindow.packages.add.package.vcs.git") -> "git+"
        message("python.toolwindow.packages.add.package.vcs.svn") -> "svn+"
        message("python.toolwindow.packages.add.package.vcs.hg") -> "hg+"
        message("python.toolwindow.packages.add.package.vcs.bzr") -> "bzr+"
        else -> throw IllegalStateException("Unknown VCS")
      }

      return PythonVcsPackageSpecification(link, link, prefix, editable)
    }
    return null
  }

  private fun showInstallFromDiscDialog(service: PyPackagingToolWindowService): PythonLocalPackageSpecification? {
    var editable = false

    val textField = TextFieldWithBrowseButton().apply {
      addBrowseFolderListener(message("python.toolwindow.packages.add.package.path.selector"), "", service.project,
                              FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor())
    }
    val panel = panel {
      row(message("python.toolwindow.packages.add.package.path")) {
        cell(textField)
          .columns(COLUMNS_MEDIUM)
          .align(AlignX.FILL)
      }
      row {
        checkBox(message("python.toolwindow.packages.add.package.as.editable"))
          .bindSelected({ editable }, { editable = it })
      }
    }

    val shouldInstall = dialog(message("python.toolwindow.packages.add.package.dialog.title"), panel, project = service.project, resizable = true).showAndGet()
    return if (shouldInstall) PythonLocalPackageSpecification(textField.text, textField.text, editable) else null
  }

  private fun trackOrientation(service: PyPackagingToolWindowService) {
    service.project.messageBus.connect(service).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
      var myHorizontal = true
      override fun stateChanged(toolWindowManager: ToolWindowManager) {
        val toolWindow = toolWindowManager.getToolWindow("Python Packages")
        if (toolWindow == null || toolWindow.isDisposed) return
        val isHorizontal = toolWindow.anchor.isHorizontal

        if (myHorizontal != isHorizontal) {
          myHorizontal = isHorizontal
          val content = toolWindow.contentManager.contents.find { it?.component is PyPackagingToolWindowPanel }
          val panel = content?.component as? PyPackagingToolWindowPanel ?: return
          panel.initOrientation(service, myHorizontal)
        }
      }
    })
  }

  fun packageSelected(selectedPackage: DisplayablePackage) {
    descriptionController.setPackage(pyPackage = selectedPackage)

    val service = project.service<PyPackagingToolWindowService>()
    packagingScope.launch {
      val packageDetails = service.detailsForPackage(selectedPackage)

      withContext(Dispatchers.EDT) {
        descriptionController.setPackageDetails(packageDetails)

        if (splitter?.secondComponent != rightPanel) {
          splitter!!.secondComponent = rightPanel
        }
      }
    }
  }


  fun showSearchResult(installed: List<InstalledPackage>, repoData: List<PyPackagesViewData>) {
    packageListController.showSearchResult(installed, repoData)
  }

  fun resetSearch(installed: List<InstalledPackage>, repos: List<PyPackagesViewData>) {
    packageListController.resetSearch(installed, repos)
  }


  fun setEmpty() {
    splitter?.secondComponent = noPackagePanel
  }

  override fun dispose() {
    packagingScope.cancel()
  }

  internal suspend fun recreateModulePanel() {
    val newPanel = createLeftPanel(project.service<PyPackagingToolWindowService>())
    withContext(Dispatchers.Main) {
      leftPanel = newPanel
      splitter?.firstComponent = leftPanel
      splitter?.repaint()
    }
  }

  fun selectPackageName(name: String) {
    this.packageListController.selectPackage(name)
  }

  companion object {
    private const val HORIZONTAL_SPLITTER_KEY = "Python.PackagingToolWindow.Horizontal"
    private const val VERTICAL_SPLITTER_KEY = "Python.PackagingToolWindow.Vertical"

    val NO_DESCRIPTION: String
      get() = message("python.toolwindow.packages.no.description.placeholder")
  }
}

