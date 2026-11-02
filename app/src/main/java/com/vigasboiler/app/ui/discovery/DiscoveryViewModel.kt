package com.vigasboiler.app.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.vigasboiler.app.discovery.DiscoveryRepository
import com.vigasboiler.app.discovery.DumpEntry
import com.vigasboiler.app.discovery.DumpExporter

data class DiscoveryUiState(
    val running: Boolean = false,
    val entries: List<DumpEntry> = emptyList(),
    val finishedAtMillis: Long? = null,
    val redactExport: Boolean = true,
    val exporting: Boolean = false,
    val error: String? = null,
    /** The last request was refused because a run was in progress or finished under a minute ago. */
    val skipped: Boolean = false,
)

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val repository: DiscoveryRepository,
    private val exporter: DumpExporter,
) : ViewModel() {

    private val _ui = MutableStateFlow(DiscoveryUiState())
    val ui: StateFlow<DiscoveryUiState> = _ui.asStateFlow()

    /** One-shot: the zip to hand to the share sheet. */
    private val _shareFile = MutableStateFlow<File?>(null)
    val shareFile: StateFlow<File?> = _shareFile.asStateFlow()

    init {
        val last = repository.lastManifest()
        if (last != null) {
            _ui.update { it.copy(entries = last.entries, finishedAtMillis = last.createdAtMillis) }
        } else {
            // First start after login: discover once automatically.
            runDiscovery()
        }
    }

    fun runDiscovery() {
        if (_ui.value.running) return
        _ui.update { it.copy(running = true, entries = emptyList(), error = null, skipped = false) }
        viewModelScope.launch {
            try {
                val manifest = repository.runIfAllowed { entry -> _ui.update { it.copy(entries = it.entries + entry) } }
                if (manifest != null) {
                    _ui.update { it.copy(running = false, finishedAtMillis = manifest.createdAtMillis) }
                } else {
                    // Another run is in progress or one finished moments ago: show that one instead.
                    val last = repository.lastManifest()
                    _ui.update {
                        it.copy(
                            running = false,
                            skipped = true,
                            entries = last?.entries.orEmpty(),
                            finishedAtMillis = last?.createdAtMillis,
                        )
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun setRedactExport(redact: Boolean) = _ui.update { it.copy(redactExport = redact) }

    fun export() {
        if (_ui.value.exporting) return
        _ui.update { it.copy(exporting = true, error = null) }
        viewModelScope.launch {
            runCatching { exporter.export(_ui.value.redactExport) }
                .onSuccess { _shareFile.value = it }
                .onFailure { e -> _ui.update { it.copy(error = e.message) } }
            _ui.update { it.copy(exporting = false) }
        }
    }

    fun onShareHandled() {
        _shareFile.value = null
    }
}
