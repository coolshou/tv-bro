package com.phlox.tvwebbrowser.activity.main

import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.WebTabState
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.utils.LogUtils
import com.phlox.tvwebbrowser.utils.observable.ObservableList
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

class TabsModel : ActiveModel() {
  companion object {
    const val STATE_JSON = "state.json"
    var TAG: String = TabsModel::class.java.simpleName
  }

  var loaded = false
  var incognitoMode = false
  val currentTab = ObservableValue<WebTabState?>(null)
  val tabsStates = ObservableList<WebTabState>()

  fun loadState() = modelScope.launch(Dispatchers.Main) {
    if (loaded) return@launch
    val tabsDao = AppDatabase.db.tabsDao()
    val stateFile = File(TVBro.instance.filesDir, STATE_JSON)
    if (stateFile.exists()) {
      val tabsStatesLoadedFromLegacyJson = async(Dispatchers.IO) {
        val tabsStates = ArrayList<WebTabState>()
        try {
          val storeStr = FileInputStream(stateFile).bufferedReader().use { it.readText() }
          val store = JSONObject(storeStr)
          val tabsStore = store.getJSONArray("tabs")
          for (i in 0 until tabsStore.length()) {
            val tab = WebTabState(TVBro.instance, tabsStore.getJSONObject(i))
            tabsStates.add(tab)
          }
        } catch (e: Exception) {
          e.printStackTrace()
          LogUtils.recordException(e)
        }
        stateFile.delete()
        tabsStates
      }.await()
      //tabsDao.deleteAll(incognitoMode)
      tabsStatesLoadedFromLegacyJson.forEachIndexed { index, webTabState ->
        webTabState.position = index
        tabsDao.insert(webTabState)
      }
    }
    tabsStates.addAll(tabsDao.getAll(incognitoMode))
    loaded = true
  }

  fun saveTab(tab: WebTabState, saveAlsoSelectionAndPositions: Boolean = false) {
    modelScope.launch(Dispatchers.Main) {
      val tabsDB = AppDatabase.db.tabsDao()

      if (saveAlsoSelectionAndPositions) {
        tabsDB.unselectAll(incognitoMode)
      }
      tab.saveWebViewStateToFile()
      if (tab.id != 0L ) {
        tabsDB.update(tab)
      } else {
        tab.id = tabsDB.insert(tab)
      }

      if (saveAlsoSelectionAndPositions) {
        tabsDB.updatePositions(tabsStates)
      }
    }
  }

  fun onCloseTab(tab: WebTabState) {
    tabsStates.remove(tab)
    modelScope.launch(Dispatchers.Main) {
      val tabsDB = AppDatabase.db.tabsDao()
      tabsDB.delete(tab)
      launch { tab.removeFiles() }
    }
  }

  fun onCloseAllTabs() {
    val tabsClone = ArrayList(tabsStates)
    tabsStates.clear()
    modelScope.launch(Dispatchers.Main) {
      val tabsDB = AppDatabase.db.tabsDao()
      tabsDB.deleteAll(incognitoMode)
      launch { tabsClone.forEach { it.removeFiles() } }
    }
  }

  fun onDetachActivity() {
    for (tab in tabsStates) {
      tab.recycleWebView()
    }
  }
}