package com.redballoons.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "RedBalloonsSettings",
    storages = [Storage("redballoons-plugin.xml")]
)
class RedBalloonsSettings : PersistentStateComponent<RedBalloonsSettings> {
    var opencodeCliPath: String = "opencode"
    var modelName: String = ""

    override fun getState(): RedBalloonsSettings = this

    override fun loadState(state: RedBalloonsSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): RedBalloonsSettings =
            ApplicationManager.getApplication().getService(RedBalloonsSettings::class.java)
    }
}