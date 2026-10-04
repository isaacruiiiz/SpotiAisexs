package com.spotiaisexs.app.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotiaisexs.app.data.connect.ConnectPlayback
import com.spotiaisexs.app.data.connect.ConnectStatus
import com.spotiaisexs.app.data.connect.ConnectSync
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebConnectForm(
    val apiKey: String = "",
    val databaseUrl: String = "",
    val email: String = "",
    val password: String = "",
)

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connectSync: ConnectSync,
) : ViewModel() {

    val status: StateFlow<ConnectStatus> = connectSync.status
    val remotePlayback: StateFlow<ConnectPlayback?> = connectSync.remotePlayback

    private val _form = MutableStateFlow(WebConnectForm())
    val form: StateFlow<WebConnectForm> = _form.asStateFlow()

    init {
        viewModelScope.launch {
            _form.update {
                it.copy(
                    apiKey = connectSync.savedApiKey().orEmpty(),
                    databaseUrl = connectSync.savedDatabaseUrl().orEmpty(),
                    email = connectSync.savedEmail().orEmpty(),
                )
            }
        }
    }

    fun onApiKey(value: String) = _form.update { it.copy(apiKey = value.trim()) }
    fun onDatabaseUrl(value: String) = _form.update { it.copy(databaseUrl = value.trim()) }
    fun onEmail(value: String) = _form.update { it.copy(email = value.trim()) }
    fun onPassword(value: String) = _form.update { it.copy(password = value) }

    /**
     * Accepts the whole `firebaseConfig = {...}` snippet from the Firebase
     * console (or web/config.js) and fills the API key and database URL.
     */
    fun pasteFirebaseConfig(text: String) {
        val apiKey = Regex("""apiKey\s*:\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
        val dbUrl = Regex("""databaseURL\s*:\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
        _form.update {
            it.copy(
                apiKey = apiKey ?: it.apiKey,
                databaseUrl = dbUrl ?: it.databaseUrl,
            )
        }
    }

    fun connect() {
        val f = _form.value
        viewModelScope.launch {
            connectSync.connect(f.apiKey, f.databaseUrl, f.email, f.password)
            _form.update { it.copy(password = "") }
        }
    }

    fun disconnect() = connectSync.disconnect()

    fun togglePlayPause() = connectSync.sendCommand("toggle")
    fun next() = connectSync.sendCommand("next")
    fun previous() = connectSync.sendCommand("previous")
    fun playHere() = connectSync.playHere()
}
