package com.darkrockstudios.apps.c2paverify.repository

import com.darkrockstudios.apps.c2paverify.model.c2pa.C2paAssertion
import com.darkrockstudios.apps.c2paverify.model.c2pa.C2paIngredient
import com.darkrockstudios.apps.c2paverify.model.c2pa.C2paManifest
import com.darkrockstudios.apps.c2paverify.model.c2pa.C2paManifestData
import com.darkrockstudios.apps.c2paverify.model.c2pa.ManifestValidationState
import com.darkrockstudios.apps.c2paverify.model.c2pa.SignatureInfo
import com.darkrockstudios.apps.c2paverify.model.c2pa.ValidationCategory
import com.darkrockstudios.apps.c2paverify.model.c2pa.ValidationIssue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure parser from the c2pa-android `reader.json()` manifest-store payload into the domain
 * [C2paManifestData]. Traverses [JsonElement] defensively (rather than strict DTOs) because the
 * C2PA schema carries many optional/version-varying fields. Validated against real on-device
 * captures in `app/src/test/resources/c2pa/captured`.
 */
class C2paManifestParser(private val json: Json) {

	fun parse(
		manifestJson: String,
		detailedJson: String? = null,
		verificationIncomplete: Boolean = false,
	): C2paManifestData {
		val root = json.parseToJsonElement(manifestJson).jsonObject

		val activeId = root.string("active_manifest")
		val manifests = root["manifests"]?.asObjectOrNull()
			?.map { (id, element) -> parseManifest(id, element.jsonObject) }
			.orEmpty()

		val validationState = when (root.string("validation_state")?.lowercase()) {
			"valid", "trusted" -> ManifestValidationState.VALID
			"invalid" -> ManifestValidationState.INVALID
			else -> ManifestValidationState.UNKNOWN
		}

		return C2paManifestData(
			activeManifestId = activeId,
			manifests = manifests,
			validationState = validationState,
			validationIssues = parseValidationIssues(root),
			rawManifestJson = manifestJson,
			rawDetailedJson = detailedJson,
			verificationIncomplete = verificationIncomplete,
		)
	}

	private fun parseManifest(id: String, obj: JsonObject): C2paManifest = C2paManifest(
		id = id,
		title = obj.string("title"),
		format = obj.string("format"),
		claimGenerator = obj.string("claim_generator"),
		instanceId = obj.string("instance_id"),
		signature = obj["signature_info"]?.asObjectOrNull()?.let { sig ->
			SignatureInfo(
				issuer = sig.string("issuer"),
				commonName = sig.string("common_name"),
				algorithm = sig.string("alg"),
				certSerialNumber = sig.string("cert_serial_number"),
				time = sig.string("time"),
			)
		},
		assertions = obj["assertions"]?.asArrayOrNull()?.mapNotNull { el ->
			val a = el.asObjectOrNull() ?: return@mapNotNull null
			val label = a.string("label") ?: return@mapNotNull null
			C2paAssertion(label = label, kind = a.string("kind"), data = a["data"])
		}.orEmpty(),
		ingredients = obj["ingredients"]?.asArrayOrNull()?.mapNotNull { el ->
			val i = el.asObjectOrNull() ?: return@mapNotNull null
			C2paIngredient(
				title = i.string("title"),
				format = i.string("format"),
				relationship = i.string("relationship"),
				instanceId = i.string("instance_id"),
				documentId = i.string("document_id"),
				activeManifest = i.string("active_manifest"),
			)
		}.orEmpty(),
	)

	private fun parseValidationIssues(root: JsonObject): List<ValidationIssue> {
		val issues = LinkedHashMap<String, ValidationIssue>()
		fun add(issue: ValidationIssue) {
			val key = "${issue.code}|${issue.url}"
			// Prefer a categorized entry over an UNKNOWN one for the same code+url.
			val existing = issues[key]
			if (existing == null || existing.category == ValidationCategory.UNKNOWN) issues[key] = issue
		}

		// Preferred: structured validation_results (categorized).
		root["validation_results"]?.asObjectOrNull()?.let { results ->
			results["activeManifest"]?.asObjectOrNull()?.let { addCategorized(it, ::add) }
			results["ingredientDeltas"]?.asArrayOrNull()?.forEach { delta ->
				delta.asObjectOrNull()?.get("validationDeltas")?.asObjectOrNull()
					?.let { addCategorized(it, ::add) }
			}
		}

		// Fallback / supplement: flat validation_status array (uncategorized).
		root["validation_status"]?.asArrayOrNull()?.forEach { el ->
			el.asObjectOrNull()?.let { add(it.toIssue(ValidationCategory.UNKNOWN)) }
		}

		return issues.values.toList()
	}

	private fun addCategorized(scope: JsonObject, add: (ValidationIssue) -> Unit) {
		scope["success"]?.asArrayOrNull()?.forEach { it.asObjectOrNull()?.let { o -> add(o.toIssue(ValidationCategory.SUCCESS)) } }
		scope["informational"]?.asArrayOrNull()?.forEach { it.asObjectOrNull()?.let { o -> add(o.toIssue(ValidationCategory.INFORMATIONAL)) } }
		scope["failure"]?.asArrayOrNull()?.forEach { it.asObjectOrNull()?.let { o -> add(o.toIssue(ValidationCategory.FAILURE)) } }
	}

	private fun JsonObject.toIssue(category: ValidationCategory) = ValidationIssue(
		code = string("code").orEmpty(),
		explanation = string("explanation"),
		url = string("url"),
		category = category,
	)

	// --- JsonElement helpers ---
	private fun JsonObject.string(key: String): String? =
		this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotEmpty() }

	private fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
	private fun JsonElement.asArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()
}
