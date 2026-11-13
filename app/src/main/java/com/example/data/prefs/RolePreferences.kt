package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.DeviceRole
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.safeNestDataStore: DataStore<Preferences> by preferencesDataStore(name = "safenest_prefs")

data class LocalDeviceConfig(
  val role: DeviceRole = DeviceRole.UNSELECTED,
  val pairedFamilyId: String = "",
  val pairedDeviceId: String = "",
  val themePreference: ThemePreference = ThemePreference.SYSTEM,
)

class RolePreferences(private val context: Context) {

  val configFlow: Flow<LocalDeviceConfig> = context.safeNestDataStore.data.map { prefs ->
    val roleStr = prefs[KEY_ROLE] ?: DeviceRole.UNSELECTED.value
    val role = DeviceRole.entries.firstOrNull { it.value == roleStr } ?: DeviceRole.UNSELECTED
    val themeStr = prefs[KEY_THEME] ?: ThemePreference.SYSTEM.name
    val theme = ThemePreference.entries.firstOrNull { it.name == themeStr } ?: ThemePreference.SYSTEM
    LocalDeviceConfig(
      role = role,
      pairedFamilyId = prefs[KEY_PAIRED_FAMILY_ID] ?: "",
      pairedDeviceId = prefs[KEY_PAIRED_DEVICE_ID] ?: "",
      themePreference = theme,
    )
  }

  suspend fun setRole(role: DeviceRole) {
    context.safeNestDataStore.edit { prefs ->
      prefs[KEY_ROLE] = role.value
    }
  }

  suspend fun setPairedDevice(familyId: String, deviceId: String) {
    context.safeNestDataStore.edit { prefs ->
      prefs[KEY_ROLE] = DeviceRole.CHILD.value
      prefs[KEY_PAIRED_FAMILY_ID] = familyId
      prefs[KEY_PAIRED_DEVICE_ID] = deviceId
    }
  }

  suspend fun clearPairedDevice() {
    context.safeNestDataStore.edit { prefs ->
      prefs.remove(KEY_PAIRED_FAMILY_ID)
      prefs.remove(KEY_PAIRED_DEVICE_ID)
    }
  }

  suspend fun setThemePreference(theme: ThemePreference) {
    context.safeNestDataStore.edit { prefs ->
      prefs[KEY_THEME] = theme.name
    }
  }

  private companion object {
    val KEY_ROLE = stringPreferencesKey("device_role")
    val KEY_PAIRED_FAMILY_ID = stringPreferencesKey("paired_family_id")
    val KEY_PAIRED_DEVICE_ID = stringPreferencesKey("paired_device_id")
    val KEY_THEME = stringPreferencesKey("theme_preference")
  }
}
