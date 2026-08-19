package neth.iecal.curbox.ui.fragments.main.usage

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.DataStoreManager
class SetupShortcutViewModel(application: Application) : AndroidViewModel(application) {
    private var dataStoreManager = DataStoreManager(application)
    
    private val _settings = MutableStateFlow<Settings?>(null)
    val settings: StateFlow<Settings?> = _settings
    
    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest {
                _settings.value = it
            }
        }
    }
    
    fun toggleAppGroup(groupId: String, isActive: Boolean) {
        viewModelScope.launch {
            val currentSettings = _settings.value ?: return@launch
            val updatedRules = currentSettings.appRuleSnapshot.appRules.map { rule ->
                val scope = rule.effectiveScope()
                if (rule.appGroupId == groupId || groupId in scope.includedGroupIds) {
                    rule.copy(isActive = isActive)
                } else {
                    rule
                }
            }
            dataStoreManager.updateAppRuleSnapshot(
                currentSettings.appRuleSnapshot.copy(appRules = updatedRules)
            )
        }
    }

    fun toggleGrayscaleGroup(groupId: String, isActive: Boolean) {
        viewModelScope.launch {
            val currentSettings = _settings.value ?: return@launch
            val updatedGroups = currentSettings.grayscaleGroups.map {
                if (it.groupId == groupId) it.copy(isActive = isActive) else it
            }
            dataStoreManager.updateGrayscaleGroups(updatedGroups)
            getApplication<Application>().sendBroadcast(Intent("neth.iecal.curbox.ACTION_REFRESH_GRAYSCALE"))
        }
    }
}
