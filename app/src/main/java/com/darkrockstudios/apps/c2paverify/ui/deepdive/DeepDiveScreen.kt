package com.darkrockstudios.apps.c2paverify.ui.deepdive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darkrockstudios.apps.c2paverify.R
import com.darkrockstudios.apps.c2paverify.model.assertion.AssertionParser
import com.darkrockstudios.apps.c2paverify.model.assertion.ParsedAction
import com.darkrockstudios.apps.c2paverify.model.c2pa.C2paAssertion
import com.darkrockstudios.apps.c2paverify.model.c2pa.C2paManifestData
import com.darkrockstudios.apps.c2paverify.model.c2pa.ValidationCategory
import com.darkrockstudios.apps.c2paverify.model.summary.OverallStatus
import com.darkrockstudios.apps.c2paverify.model.trust.TrustRule
import com.darkrockstudios.apps.c2paverify.ui.common.plus
import com.darkrockstudios.apps.c2paverify.ui.inspection.InspectionUiState
import com.darkrockstudios.apps.c2paverify.ui.inspection.InspectionViewModel
import com.darkrockstudios.apps.c2paverify.ui.inspection.SummaryCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

object DeepDive {
	const val TAG_LIST = "deep_dive_list"
}

/**
 * Full manifest exploration: manifest metadata, signature/certificate, assertions, ingredients,
 * validation issues, and a raw-JSON explorer. Shares [InspectionViewModel] with the viewer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepDiveScreen(
	viewModel: InspectionViewModel,
	onBack: () -> Unit,
	showBack: Boolean = true,
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	// Held here rather than inside the LazyColumn: a trust change re-inspects, which passes through
	// Loading and takes the list out of composition, and an inner state would lose the scroll there.
	val listState = rememberLazyListState()

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.details_title)) },
				navigationIcon = {
					if (showBack) {
						IconButton(onClick = onBack) {
							Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
						}
					}
				},
			)
		},
	) { innerPadding ->
		val result = (state as? InspectionUiState.Loaded)?.result
		val manifest = result?.manifest
		if (manifest == null) {
			val message = (state as? InspectionUiState.Error)
				?.let { stringResource(R.string.inspect_error, it.message) }
				?: stringResource(R.string.status_no_manifest_body)
			Column(Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
				Card(Modifier.fillMaxWidth()) {
					Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
				}
			}
			return@Scaffold
		}

		// Wrap the whole detail in a SelectionContainer so users can select/copy the real data
		// (signer, serial, hashes, JSON…). Pure labels are excluded via DisableSelection below.
		SelectionContainer(Modifier.fillMaxSize()) {
			LazyColumn(
				modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding)
					.testTag(DeepDive.TAG_LIST),
				state = listState,
				contentPadding = innerPadding + PaddingValues(16.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				// Lead with the high-level state (origin chips + verdict, same as the viewer card) and
				// the action chain — the parts most people actually want — then the technical detail.
				item { DisableSelection { SummaryCard(result.summary) } }
				item { ActionsTimelineSection(manifest) }
				item { ManifestSection(manifest) }
			item {
				SignatureSection(
					manifest = manifest,
					status = result.summary.status,
					hasOverride = result.signerHasOverride,
					onAllow = { viewModel.setSignerRule(TrustRule.ALLOW) },
					onDeny = { viewModel.setSignerRule(TrustRule.DENY) },
					onClear = { viewModel.setSignerRule(null) },
				)
			}
			item { AssertionsSection(manifest) }
			item { IngredientsSection(manifest) }
			item { ValidationSection(manifest) }
			item { RawJsonSection(manifest) }
			}
		}
	}
}

@Composable
private fun ManifestSection(manifest: C2paManifestData) {
	val m = manifest.activeManifest
	SectionCard(stringResource(R.string.section_manifest), Icons.Filled.Info) {
		KeyValue(stringResource(R.string.label_title), m?.title)
		KeyValue(stringResource(R.string.label_format), m?.format)
		KeyValue(stringResource(R.string.label_generator), m?.claimGenerator)
		KeyValue(stringResource(R.string.label_instance), m?.instanceId)
	}
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SignatureSection(
	manifest: C2paManifestData,
	status: OverallStatus,
	hasOverride: Boolean,
	onAllow: () -> Unit,
	onDeny: () -> Unit,
	onClear: () -> Unit,
) {
	val sig = manifest.activeManifest?.signature
	// Effective trust (reflects any user allow/deny override), from the computed verdict.
	val trust = when (status) {
		OverallStatus.SIGNED_TRUSTED -> stringResource(R.string.trust_trusted)
		OverallStatus.SIGNED_UNTRUSTED, OverallStatus.TAMPERED_INVALID -> stringResource(R.string.trust_untrusted)
		OverallStatus.NO_MANIFEST -> stringResource(R.string.trust_unknown)
	}
	SectionCard(stringResource(R.string.section_signature), Icons.Filled.Lock) {
		KeyValue(stringResource(R.string.label_issuer), sig?.issuer)
		KeyValue(stringResource(R.string.label_common_name), sig?.commonName)
		KeyValue(stringResource(R.string.label_algorithm), sig?.algorithm)
		KeyValue(stringResource(R.string.label_serial), sig?.certSerialNumber)
		KeyValue(stringResource(R.string.label_signed_time), sig?.time)
		KeyValue(stringResource(R.string.label_trust), trust)
		if (sig != null) {
			// Only show the actions that would change something: "Trust" when not already trusted,
			// "Don't trust" when trusted, and "Clear override" only when a user rule exists.
			val showTrust = status == OverallStatus.SIGNED_UNTRUSTED
			val showDeny = status == OverallStatus.SIGNED_TRUSTED
			if (showTrust || showDeny || hasOverride) {
				// FlowRow so the actions wrap onto another line in a narrow (tablet) detail pane
				// instead of overflowing — a plain Row clipped "Clear override" and inflated the card.
				FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					if (showTrust) {
						TextButton(onClick = onAllow) { Text(stringResource(R.string.action_trust_signer)) }
					}
					if (showDeny) {
						TextButton(onClick = onDeny) { Text(stringResource(R.string.action_distrust_signer)) }
					}
					if (hasOverride) {
						TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear_override)) }
					}
				}
			}
		}
	}
}

@Composable
private fun AssertionsSection(manifest: C2paManifestData) {
	val assertions = manifest.activeManifest?.assertions.orEmpty()
	ExpandableCard(stringResource(R.string.section_assertions, assertions.size), Icons.AutoMirrored.Filled.List) {
		if (assertions.isEmpty()) {
			Text(stringResource(R.string.empty_section), style = MaterialTheme.typography.bodySmall)
		}
		assertions.forEachIndexed { index, a ->
			if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
			AssertionEntry(a, index)
		}
	}
}

/**
 * One assertion, shown uniformly as raw JSON behind a per-entry toggle (collapsed by default — some,
 * like content hashes, are large and noisy). The friendly `c2pa.actions` chain is presented up top
 * in [ActionsTimelineSection], so it isn't duplicated here.
 */
@Composable
private fun AssertionEntry(assertion: C2paAssertion, index: Int) {
	// Show the technical label as a caption only when the friendly title differs (for unrecognised
	// assertion types the title falls back to the raw label, which would just be a duplicate).
	val title = assertionTitle(assertion.label)
	val showLabel = title != assertion.label
	var expanded by rememberSaveable(index, assertion.label) { mutableStateOf(false) }
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(Modifier.weight(1f)) {
			Text(title, style = MaterialTheme.typography.titleSmall)
			if (showLabel) {
				Text(assertion.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
		TextButton(onClick = { expanded = !expanded }) {
			Text(stringResource(if (expanded) R.string.hide else R.string.show))
		}
	}
	AnimatedVisibility(visible = expanded) { Mono(prettyPrint(assertion.data)) }
}

/** Friendly category name for an assertion label; falls back to the raw label. */
@Composable
private fun assertionTitle(label: String): String = when {
	AssertionParser.isActionsAssertion(label) -> stringResource(R.string.assertion_title_actions)
	label == "stds.schema-org.CreativeWork" -> stringResource(R.string.assertion_title_creative_work)
	label.startsWith("c2pa.thumbnail") -> stringResource(R.string.assertion_title_thumbnail)
	label.startsWith("c2pa.hash") -> stringResource(R.string.assertion_title_hash)
	else -> label
}

/**
 * The action chain as a tappable vertical timeline — the part most people want to explore. Each step
 * is a coloured node (creation / edit / AI) connected by a rail, with its details; tapping a step
 * opens a plain-language explanation of what that kind of action means.
 */
@Composable
private fun ActionsTimelineSection(manifest: C2paManifestData) {
	val actions = remember(manifest) {
		manifest.manifestChain.flatMap { m ->
			m.assertions
				.filter { AssertionParser.isActionsAssertion(it.label) }
				.flatMap { AssertionParser.parseActions(it.data) }
		}
	}
	var info by remember { mutableStateOf<ParsedAction?>(null) }
	info?.let { ActionInfoDialog(it, onDismiss = { info = null }) }

	Card(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			SectionHeader(stringResource(R.string.actions_timeline_title), Icons.AutoMirrored.Filled.List)
			if (actions.isEmpty()) {
				DisableSelection {
					Text(
						stringResource(R.string.actions_timeline_empty),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			} else {
				DisableSelection {
					Text(
						stringResource(R.string.actions_timeline_subtitle),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				Column {
					actions.forEachIndexed { i, action ->
						ActionStep(
							action = action,
							isFirst = i == 0,
							isLast = i == actions.lastIndex,
							onClick = { info = action },
						)
					}
				}
			}
		}
	}
}

@Composable
private fun ActionStep(action: ParsedAction, isFirst: Boolean, isLast: Boolean, onClick: () -> Unit) {
	val node = actionNode(action)
	val lineColor = MaterialTheme.colorScheme.outlineVariant
	Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
		// Rail: a continuous line threaded through a coloured node. Top stub hidden on the first step,
		// bottom connector hidden on the last, so the line begins and ends at the end nodes.
		Column(
			modifier = Modifier.width(36.dp).fillMaxHeight(),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Box(Modifier.width(2.dp).height(14.dp).background(if (isFirst) Color.Transparent else lineColor))
			Box(
				modifier = Modifier.size(30.dp).clip(CircleShape).background(node.background),
				contentAlignment = Alignment.Center,
			) {
				Icon(actionIcon(action.code), contentDescription = null, modifier = Modifier.size(17.dp), tint = node.foreground)
			}
			Box(Modifier.width(2.dp).weight(1f).background(if (isLast) Color.Transparent else lineColor))
		}
		Spacer(Modifier.width(12.dp))
		Column(
			modifier = Modifier
				.weight(1f)
				.clip(RoundedCornerShape(8.dp))
				.clickable(onClick = onClick)
				.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 18.dp),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(action.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
				Icon(
					Icons.Filled.Info,
					contentDescription = stringResource(R.string.show),
					modifier = Modifier.size(15.dp),
					tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
				)
			}
			action.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
			action.softwareAgent?.let {
				Text(
					stringResource(R.string.assertion_using, it),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			action.whenIso?.let {
				Text(
					stringResource(R.string.assertion_when, it),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			action.digitalSource?.let { source ->
				if (source.isAi) {
					AssistChip(
						onClick = onClick,
						label = { Text(source.label) },
						leadingIcon = {
							Icon(
								painter = painterResource(R.drawable.ic_auto_awesome),
								contentDescription = null,
								modifier = Modifier.size(AssistChipDefaults.IconSize),
							)
						},
						modifier = Modifier.padding(top = 2.dp),
					)
				} else {
					Text(
						stringResource(R.string.assertion_source, source.label),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}

@Composable
private fun ActionInfoDialog(action: ParsedAction, onDismiss: () -> Unit) {
	AlertDialog(
		onDismissRequest = onDismiss,
		icon = { Icon(actionIcon(action.code), contentDescription = null) },
		title = { Text(action.label) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(actionExplanation(action.code))
				action.description?.let {
					Text(
						stringResource(R.string.action_recorded_note, it),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) }
		},
	)
}

private class NodeColors(val background: Color, val foreground: Color)

/** Node colour by action kind: AI source → purple, creation → primary, edit → tertiary, else neutral. */
@Composable
private fun actionNode(action: ParsedAction): NodeColors = when {
	action.digitalSource?.isAi == true -> NodeColors(Color(0xFF7C4DFF), Color.White)
	action.code in CREATION_ACTIONS -> NodeColors(
		MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary,
	)

	action.code in EDITING_ACTIONS -> NodeColors(
		MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary,
	)

	else -> NodeColors(
		MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary,
	)
}

/** Plain-language explanation of what a C2PA action code means; falls back to a generic line. */
@Composable
private fun actionExplanation(code: String): String = when (code) {
	"c2pa.created" -> stringResource(R.string.action_explain_created)
	"c2pa.opened" -> stringResource(R.string.action_explain_opened)
	"c2pa.placed" -> stringResource(R.string.action_explain_placed)
	"c2pa.removed" -> stringResource(R.string.action_explain_removed)
	"c2pa.edited" -> stringResource(R.string.action_explain_edited)
	"c2pa.cropped" -> stringResource(R.string.action_explain_cropped)
	"c2pa.color_adjustments" -> stringResource(R.string.action_explain_color_adjustments)
	"c2pa.drawing" -> stringResource(R.string.action_explain_drawing)
	"c2pa.filtered" -> stringResource(R.string.action_explain_filtered)
	"c2pa.resized" -> stringResource(R.string.action_explain_resized)
	"c2pa.orientation" -> stringResource(R.string.action_explain_orientation)
	"c2pa.watermarked" -> stringResource(R.string.action_explain_watermarked)
	"c2pa.metadata" -> stringResource(R.string.action_explain_metadata)
	"c2pa.redacted" -> stringResource(R.string.action_explain_redacted)
	"c2pa.converted" -> stringResource(R.string.action_explain_converted)
	"c2pa.transcoded" -> stringResource(R.string.action_explain_transcoded)
	"c2pa.formatted", "c2pa.repackaged" -> stringResource(R.string.action_explain_formatted)
	"c2pa.published" -> stringResource(R.string.action_explain_published)
	"c2pa.managed" -> stringResource(R.string.action_explain_managed)
	"c2pa.produced" -> stringResource(R.string.action_explain_produced)
	"c2pa.unknown" -> stringResource(R.string.action_explain_unknown)
	else -> stringResource(R.string.action_explain_generic)
}

private fun actionIcon(code: String): ImageVector = when {
	code in CREATION_ACTIONS -> Icons.Filled.Add
	code in EDITING_ACTIONS -> Icons.Filled.Edit
	else -> Icons.Filled.Info
}

private val CREATION_ACTIONS = setOf("c2pa.created", "c2pa.opened", "c2pa.placed")

private val EDITING_ACTIONS = setOf(
	"c2pa.edited", "c2pa.cropped", "c2pa.color_adjustments", "c2pa.adjustedColor",
	"c2pa.enhanced", "c2pa.drawing", "c2pa.filtered", "c2pa.resized",
	"c2pa.orientation", "c2pa.watermarked", "c2pa.metadata", "c2pa.redacted",
)

@Composable
private fun IngredientsSection(manifest: C2paManifestData) {
	val ingredients = manifest.activeManifest?.ingredients.orEmpty()
	ExpandableCard(stringResource(R.string.section_ingredients, ingredients.size), Icons.AutoMirrored.Filled.List) {
		if (ingredients.isEmpty()) {
			Text(stringResource(R.string.empty_section), style = MaterialTheme.typography.bodySmall)
		}
		ingredients.forEachIndexed { index, ing ->
			if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
			Text(ing.title ?: "—", style = MaterialTheme.typography.titleSmall)
			KeyValue(stringResource(R.string.label_format), ing.format)
			ing.relationship?.let { Text(stringResource(R.string.ingredient_relationship, it), style = MaterialTheme.typography.bodySmall) }
		}
	}
}

@Composable
private fun ValidationSection(manifest: C2paManifestData) {
	val issues = manifest.validationIssues
	// When nothing failed, the long list of green "SUCCESS" rows is just noise — collapse it by
	// default and show a satisfying green check; expand automatically when something failed.
	val allPassed = issues.isNotEmpty() && issues.none { it.category == ValidationCategory.FAILURE }
	val passedGreen = if (isSystemInDarkTheme()) Color(0xFF6FD08C) else Color(0xFF2E7D32)
	val title = if (allPassed) {
		stringResource(R.string.validation_all_passed, issues.size)
	} else {
		stringResource(R.string.section_validation, issues.size)
	}
	ExpandableCard(
		title = title,
		icon = Icons.Filled.CheckCircle,
		iconTint = if (allPassed) passedGreen else MaterialTheme.colorScheme.primary,
		initiallyExpanded = !allPassed,
	) {
		if (issues.isEmpty()) {
			Text(stringResource(R.string.empty_section), style = MaterialTheme.typography.bodySmall)
		}
		issues.forEach { issue ->
			val color = when (issue.category) {
				ValidationCategory.FAILURE -> MaterialTheme.colorScheme.error
				ValidationCategory.SUCCESS -> MaterialTheme.colorScheme.primary
				else -> MaterialTheme.colorScheme.onSurfaceVariant
			}
			Text("${issue.category.name} · ${issue.code}", style = MaterialTheme.typography.bodyMedium, color = color)
			issue.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
		}
	}
}

@Composable
private fun RawJsonSection(manifest: C2paManifestData) {
	ExpandableCard(stringResource(R.string.section_raw_json), Icons.Filled.Info, initiallyExpanded = false) {
		Mono(remember(manifest) { prettyPrint(manifest.rawDetailedJson ?: manifest.rawManifestJson) })
	}
}

// --- reusable bits ---

@Composable
private fun SectionHeader(
	title: String,
	icon: ImageVector?,
	iconTint: Color = MaterialTheme.colorScheme.primary,
	trailing: @Composable () -> Unit = {},
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			modifier = Modifier.weight(1f),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (icon != null) {
				Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = iconTint)
			}
			DisableSelection { Text(title, style = MaterialTheme.typography.titleMedium) }
		}
		trailing()
	}
}

@Composable
private fun SectionCard(title: String, icon: ImageVector? = null, content: @Composable () -> Unit) {
	Card(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			SectionHeader(title, icon)
			content()
		}
	}
}

@Composable
private fun ExpandableCard(
	title: String,
	icon: ImageVector? = null,
	iconTint: Color = MaterialTheme.colorScheme.primary,
	initiallyExpanded: Boolean = true,
	content: @Composable () -> Unit,
) {
	var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
	Card(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			SectionHeader(title, icon, iconTint) {
				TextButton(onClick = { expanded = !expanded }) {
					Text(stringResource(if (expanded) R.string.hide else R.string.show))
				}
			}
			AnimatedVisibility(visible = expanded) {
				Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
			}
		}
	}
}

@Composable
private fun KeyValue(label: String, value: String?) {
	if (value.isNullOrBlank()) return
	Column {
		DisableSelection {
			Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		Text(value, style = MaterialTheme.typography.bodyMedium)
	}
}

@Composable
private fun Mono(text: String) {
	Surface(
		color = MaterialTheme.colorScheme.surfaceVariant,
		shape = RoundedCornerShape(8.dp),
		modifier = Modifier.fillMaxWidth(),
	) {
		Text(
			text = text,
			fontFamily = FontFamily.Monospace,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier
				.horizontalScroll(rememberScrollState())
				.padding(12.dp),
		)
	}
}

private fun prettyPrint(raw: String): String =
	runCatching {
		prettyJson.encodeToString(JsonElement.serializer(), prettyJson.parseToJsonElement(raw))
	}.getOrDefault(raw)

private fun prettyPrint(element: JsonElement?): String =
	element?.let {
		runCatching { prettyJson.encodeToString(JsonElement.serializer(), it) }.getOrNull()
	} ?: "—"
