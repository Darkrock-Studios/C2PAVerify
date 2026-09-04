package com.darkrockstudios.apps.c2paverify.model.c2pa

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * ============================================================================================
 * ██  TEMPORARY WORKAROUND: DELETE THIS ENTIRE FILE ONCE c2pa-rs SHIPS THE FIX  ██
 * ============================================================================================
 *
 * WHAT IS BROKEN
 * Every released c2pa-rs (checked through c2pa-v0.90.16) resolves BMFF hash exclusions with a
 * flat hash-map lookup keyed on the literal xpath string:
 *
 *     if let Some(box_token_list) = bmff_map.get(&bmff_exclusion.xpath) {   // bmff_io.rs
 *
 * The map's keys are plain fourcc chains built by `path_from_token`: `/moov/trak`. An exclusion
 * using the spec's INDEXED notation, `/moov[1]/trak[3]`, matches no key, `get` returns `None`,
 * and because the call site is an `if let` the exclusion is SILENTLY DROPPED rather than raising.
 * The excluded box then gets folded into the hash and a perfectly intact asset reports
 * `assertion.bmffHash.mismatch`, i.e. the app screams "tampered" at a genuine camera original.
 *
 * Pixel phones write exactly this: `/moov[1]/trak[3]`, excluding the timed-metadata track.
 *
 * WHY WE CAN'T JUST BUMP THE DEPENDENCY
 * The upstream fix (contentauth/c2pa-rs#2434, "Support Xpath indices") is on `main` but was NOT
 * in any published release as of 2026-09-02, and c2pa-android 0.0.10 predates it regardless.
 *
 * WHAT THIS WORKAROUND DOES
 * Detects the exact fingerprint of the bug (the ONLY validation failures are bmff-hash
 * mismatches, AND the asset's own BMFF hash assertion carries an indexed exclusion xpath the
 * bundled library provably cannot evaluate) and downgrades the verdict from
 * [com.darkrockstudios.apps.c2paverify.model.summary.OverallStatus.TAMPERED_INVALID] to
 * `UNVERIFIABLE`. It never upgrades anything to trusted: an asset we cannot check is reported as
 * unchecked, not as good.
 *
 * ‼️ HOW TO REVERT ‼️
 * When c2pa-android ships a build carrying c2pa-rs with #2434:
 *   1. Bump `c2paAndroid` in gradle/libs.versions.toml.
 *   2. Delete this file and BmffIndexedXpathWorkaroundTest.
 *   3. Delete `OverallStatus.UNVERIFIABLE` and every `when` branch for it (SummaryCard,
 *      ViewerScreen, DeepDiveScreen, ShareableReport) plus its strings.
 *   4. Drop the `hasSpuriousBmffHashFailure` branch in SummaryFactory.buildSummary.
 * Grep for `BMFF_INDEXED_XPATH` to find every touch point.
 * ============================================================================================
 */

/**
 * True when this manifest's failure is the c2pa-rs indexed-xpath bug rather than real tampering.
 *
 * Deliberately narrow; all three must hold:
 *  1. at least one INTEGRITY failure, and every one of them is a bmff-hash mismatch (any other
 *     integrity failure means something genuinely is wrong, so we keep the tampered verdict);
 *  2. the detailed JSON exposes a `c2pa.hash.bmff*` assertion; and
 *  3. that assertion excludes at least one box by INDEXED xpath, which the bundled library
 *     silently ignores.
 *
 * Trust failures are excluded on purpose. A Pixel video off a device with no trust list configured
 * reports `signingCredential.untrusted` alongside the bogus hash mismatch; that is a statement
 * about the signer, not the content, and must not block the downgrade.
 */
val C2paManifestData.hasSpuriousBmffHashFailure: Boolean
	get() {
		val failures = integrityFailures
		if (failures.isEmpty()) return false
		if (!failures.all { it.code.startsWith(BMFF_HASH_MISMATCH_PREFIX) }) return false
		return rawDetailedJson?.let(::hasIndexedBmffExclusion) ?: false
	}

/**
 * Scans a `reader.detailedJson()` payload for a BMFF hash assertion carrying an indexed exclusion
 * xpath. Traverses defensively: a payload we can't read is reported as "no indexed exclusion",
 * which keeps the untouched (tampered) verdict rather than excusing a real failure.
 */
internal fun hasIndexedBmffExclusion(detailedJson: String): Boolean = runCatching {
	val root = Json.parseToJsonElement(detailedJson).jsonObject
	val manifests = root["manifests"] as? JsonObject ?: return@runCatching false
	manifests.values.any { manifest ->
		val store = (manifest as? JsonObject)?.get("assertion_store") as? JsonObject
			?: return@any false
		store.entries.any { (label, assertion) ->
			label.startsWith(BMFF_HASH_ASSERTION_PREFIX) && assertion.hasIndexedExclusion()
		}
	}
}.getOrDefault(false)

private fun kotlinx.serialization.json.JsonElement.hasIndexedExclusion(): Boolean = runCatching {
	jsonObject["exclusions"]?.jsonArray?.any { exclusion ->
		val xpath = exclusion.jsonObject["xpath"]?.jsonPrimitive?.content ?: return@any false
		INDEXED_XPATH_STEP.containsMatchIn(xpath)
	} == true
}.getOrDefault(false)

/** An xpath step with a positional predicate, e.g. `trak[3]` in `/moov[1]/trak[3]`. */
private val INDEXED_XPATH_STEP = Regex("""\[\d+]""")

private const val BMFF_HASH_MISMATCH_PREFIX = "assertion.bmffHash."
private const val BMFF_HASH_ASSERTION_PREFIX = "c2pa.hash.bmff"
