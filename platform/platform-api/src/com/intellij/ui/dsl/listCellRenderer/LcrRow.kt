// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.dsl.listCellRenderer

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import java.awt.Color
import javax.swing.Icon
import javax.swing.JList

@ApiStatus.Experimental
@LcrDslMarker
interface LcrRow<T> {

  enum class Gap {
    /**
     * Default gap between cells. Usages:
     * * Gap between icon and related text
     */
    DEFAULT,

    /**
     * No space
     */
    NONE
  }

  @Deprecated("Will be removed because we want to get rid of swing dependency for RemDev")
  val list: JList<out T>

  /**
   * The value of the rendering item
   */
  val value: T

  /**
   * Index of the rendering item. `-1` if ComboBox is rendering the selected item in collapsed state
   */
  val index: Int

  /**
   * `true` if the rendering item is selected
   */
  val selected: Boolean

  /**
   * `true` if the rendering item has the focus
   */
  val cellHasFocus: Boolean

  /**
   * Row background. Used if the row is not selected and on left/right sides of selected row (new UI only)
   */
  var background: Color?

  /**
   * Selection color if the row is selected or `null` otherwise
   */
  var selectionColor: Color?


  /**
   * The gap between the previous cell and the next one. Not used for the first cell
   */
  fun gap(gap: Gap)

  /**
   * Adds a cell with an icon
   */
  fun icon(icon: Icon, init: (LcrIconInitParams.() -> Unit)? = null)

  /**
   * Adds a cell with a text
   */
  fun text(text: @Nls String, init: (LcrTextInitParams.() -> Unit)? = null)

}