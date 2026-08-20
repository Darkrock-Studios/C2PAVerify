package com.darkrockstudios.apps.c2paverify.ui.inspection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkrockstudios.apps.c2paverify.model.share.ReportOverlay
import com.darkrockstudios.apps.c2paverify.model.summary.InspectionResult
import com.darkrockstudios.apps.c2paverify.model.trust.TrustRule
import com.darkrockstudios.apps.c2paverify.model.trust.authorityKeyOf
import com.darkrockstudios.apps.c2paverify.usecase.inspect.InspectPhotoUseCase
import com.darkrockstudios.apps.c2paverify.usecase.share.ShareReportUseCase
import com.darkrockstudios.apps.c2paverify.usecase.trust.ClearAuthorityRuleUseCase
import com.darkrockstudios.apps.c2paverify.usecase.trust.ObserveUserTrustRulesUseCase
import com.darkrockstudios.apps.c2paverify.usecase.trust.SetAuthorityRuleUseCase
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

sealed interface InspectionUiState {
	data object Idle : InspectionUiState
	data object Loading : InspectionUiState
	data class Loaded(val result: InspectionResult) : InspectionUiState
	data class Error(val message: String) : InspectionUiState
}

class InspectionViewModel(
	private val inspectPhoto: InspectPhotoUseCase,
	private val setAuthorityRule: SetAuthorityRuleUseCase,
	private val clearAuthorityRule: ClearAuthorityRuleUseCase,
	private val shareReport: ShareReportUseCase,
	observeUserRules: ObserveUserTrustRulesUseCase,
) : ViewModel() {

	private val _state = MutableStateFlow<InspectionUiState>(InspectionUiState.Idle)
	val state: StateFlow<InspectionUiState> = _state.asStateFlow()

	init {
		// Re-inspect the current photo whenever the user's trust rules change — including a CA
		// dis-allowed from the Trust screen — so the verdict stays in sync without an app restart.
		// drop(1) skips the initial snapshot (nothing is loaded yet at startup).
		viewModelScope.launch {
			observeUserRules().drop(1).collect {
				lastInspectedUri?.let { uri -> runInspection(uri) }
			}
		}
	}

	/** True while a shareable report is being rendered. */
	private val _sharing = MutableStateFlow(false)
	val sharing: StateFlow<Boolean> = _sharing.asStateFlow()

	/** One-shot stream of rendered report `content://` URIs for the UI to hand to a share sheet. */
	private val _shareRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
	val shareRequests: SharedFlow<String> = _shareRequests.asSharedFlow()

	/**
	 * One-shot stream of failed report renders. Decoding can fail for an asset the C2PA reader read
	 * happily (a HEIC on a device without the codec, say), and without this the share action would
	 * simply stop with nothing shown.
	 */
	private val _shareFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
	val shareFailures: SharedFlow<Unit> = _shareFailures.asSharedFlow()

	private var lastInspectedUri: String? = null

	/** Inspects [uri]; no-ops if it's already loaded for the same URI. */
	fun inspect(uri: String) {
		if (uri == lastInspectedUri && _state.value is InspectionUiState.Loaded) return
		lastInspectedUri = uri
		viewModelScope.launch { runInspection(uri) }
	}

	/** Sets (or clears, when [rule] is null) the user trust rule for the current signer. */
	fun setSignerRule(rule: TrustRule?) {
		val loaded = _state.value as? InspectionUiState.Loaded ?: return
		val signature = loaded.result.manifest?.activeManifest?.signature ?: return
		val key = authorityKeyOf(signature.issuer, signature.certSerialNumber) ?: return
		val displayName = signature.commonName ?: signature.issuer ?: key
		viewModelScope.launch {
			if (rule == null) {
				clearAuthorityRule(key)
			} else {
				setAuthorityRule(key, displayName, rule, System.currentTimeMillis())
			}
			// Re-inspection is driven by the rules observer in init (single source of truth).
		}
	}

	/**
	 * Renders the current photo + [overlay] into a shareable report and emits its URI on
	 * [shareRequests]. No-ops if nothing is loaded or a render is already in flight.
	 */
	fun shareReport(overlay: ReportOverlay) {
		val uri = lastInspectedUri ?: return
		if (_sharing.value) return
		_sharing.value = true
		viewModelScope.launch {
			runCatching { shareReport(uri, overlay) }
				.onSuccess { _shareRequests.emit(it) }
				.onFailure {
					Napier.e(throwable = it) { "Failed to render shareable report for $uri" }
					_shareFailures.emit(Unit)
				}
			_sharing.value = false
		}
	}

	private suspend fun runInspection(uri: String) {
		_state.value = InspectionUiState.Loading
		_state.value = runCatching { inspectPhoto(uri) }
			.fold(
				onSuccess = { InspectionUiState.Loaded(it) },
				onFailure = {
					Napier.e(throwable = it) { "Inspection failed for $uri" }
					InspectionUiState.Error(it.message ?: "Inspection failed")
				},
			)
	}
}
