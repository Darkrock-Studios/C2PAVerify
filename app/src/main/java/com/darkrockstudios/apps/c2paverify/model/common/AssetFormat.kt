package com.darkrockstudios.apps.c2paverify.model.common

/** What an asset actually is, which decides how (or whether) the UI can present it. */
enum class AssetKind {
	IMAGE,
	VIDEO,
	AUDIO,

	/** A bare C2PA manifest store (`.c2pa`), carrying provenance for some other asset. */
	MANIFEST,
}

/** A format the C2PA reader understands: the exact [token] to hand it, and the [kind] it denotes. */
data class AssetFormat(val token: String, val kind: AssetKind)

/**
 * Resolves the format token the C2PA reader needs from whatever a platform can say about an asset.
 *
 * The reader lowercases this token and looks it up in a fixed table, so a near-miss selects no
 * parser at all: `image/jpg`, `image/heic-sequence` and `application/octet-stream` are all misses.
 * Guessing is worse than admitting defeat, because handing JPEG's parser a HEIF reports a corrupt
 * asset rather than an unreadable one.
 *
 * Content bytes are consulted first: a header says what an asset *is*, while a MIME type or file
 * name says only what it claims to be. The tables mirror the handlers compiled into our native
 * library, which is built without the `pdf` feature, so PDF is deliberately absent.
 */
object AssetFormats {

	/** Bytes needed from the head of an asset for [fromHeader] to identify what it can. */
	const val HEADER_BYTES = 32

	private val JPEG = AssetFormat("image/jpeg", AssetKind.IMAGE)
	private val PNG = AssetFormat("image/png", AssetKind.IMAGE)
	private val GIF = AssetFormat("image/gif", AssetKind.IMAGE)
	private val JXL = AssetFormat("image/jxl", AssetKind.IMAGE)
	private val WEBP = AssetFormat("image/webp", AssetKind.IMAGE)
	private val TIFF = AssetFormat("image/tiff", AssetKind.IMAGE)
	private val DNG = AssetFormat("image/x-adobe-dng", AssetKind.IMAGE)
	private val ARW = AssetFormat("image/x-sony-arw", AssetKind.IMAGE)
	private val NEF = AssetFormat("image/x-nikon-nef", AssetKind.IMAGE)
	private val AVIF = AssetFormat("image/avif", AssetKind.IMAGE)
	private val HEIC = AssetFormat("image/heic", AssetKind.IMAGE)
	private val HEIF = AssetFormat("image/heif", AssetKind.IMAGE)
	private val SVG = AssetFormat("image/svg+xml", AssetKind.IMAGE)
	private val MP4 = AssetFormat("video/mp4", AssetKind.VIDEO)
	private val MOV = AssetFormat("video/quicktime", AssetKind.VIDEO)
	private val M4V = AssetFormat("video/x-m4v", AssetKind.VIDEO)
	private val AVI = AssetFormat("video/msvideo", AssetKind.VIDEO)
	private val M4A = AssetFormat("audio/mp4", AssetKind.AUDIO)
	private val MP3 = AssetFormat("audio/mpeg", AssetKind.AUDIO)
	private val WAV = AssetFormat("audio/wav", AssetKind.AUDIO)
	private val FLAC = AssetFormat("audio/flac", AssetKind.AUDIO)
	private val C2PA = AssetFormat("application/x-c2pa-manifest-store", AssetKind.MANIFEST)

	/** Formats sharing the BMFF parser, whose container brands alone cannot separate them. */
	private val bmff = setOf(AVIF, HEIC, HEIF, MP4, MOV, M4V, M4A)

	/**
	 * Declared MIME types, including spellings Android emits that the reader itself does not accept
	 * (`image/jpg`, the HEIF sequence types) and which therefore have to be translated rather than
	 * passed straight through.
	 */
	private val byMimeType: Map<String, AssetFormat> = mapOf(
		"image/jpeg" to JPEG,
		"image/jpg" to JPEG,
		"image/png" to PNG,
		"image/gif" to GIF,
		"image/jxl" to JXL,
		"image/webp" to WEBP,
		"image/tiff" to TIFF,
		"image/dng" to DNG,
		"image/x-adobe-dng" to DNG,
		"image/x-sony-arw" to ARW,
		"image/x-nikon-nef" to NEF,
		"image/avif" to AVIF,
		"image/heic" to HEIC,
		"image/heic-sequence" to HEIC,
		"image/heif" to HEIF,
		"image/heif-sequence" to HEIF,
		"image/svg+xml" to SVG,
		"application/svg+xml" to SVG,
		"text/xml" to SVG,
		"application/xml" to SVG,
		"application/xhtml+xml" to SVG,
		"video/mp4" to MP4,
		"application/mp4" to MP4,
		"video/quicktime" to MOV,
		"video/x-m4v" to M4V,
		"video/avi" to AVI,
		"video/msvideo" to AVI,
		"video/x-msvideo" to AVI,
		"application/x-troff-msvideo" to AVI,
		"audio/mp4" to M4A,
		"audio/mpeg" to MP3,
		"audio/wav" to WAV,
		"audio/wave" to WAV,
		"audio/x-wav" to WAV,
		"audio/vnd.wave" to WAV,
		"audio/flac" to FLAC,
		"application/c2pa" to C2PA,
		"application/x-c2pa-manifest-store" to C2PA,
	)

	private val byExtension: Map<String, AssetFormat> = mapOf(
		"jpg" to JPEG, "jpeg" to JPEG, "jpe" to JPEG,
		"png" to PNG,
		"gif" to GIF,
		"jxl" to JXL,
		"webp" to WEBP,
		"tif" to TIFF, "tiff" to TIFF,
		"dng" to DNG,
		"arw" to ARW,
		"nef" to NEF,
		"avif" to AVIF,
		"heic" to HEIC, "heif" to HEIF,
		"svg" to SVG, "xml" to SVG, "xhtml" to SVG,
		"mp4" to MP4, "m4v" to M4V, "mov" to MOV, "avi" to AVI,
		"m4a" to M4A, "mp3" to MP3, "wav" to WAV, "flac" to FLAC,
		"c2pa" to C2PA,
	)

	/**
	 * Best available format for an asset, or null when nothing identifies it. [header] need only be
	 * the first [HEADER_BYTES] bytes.
	 */
	fun resolve(
		mimeType: String? = null,
		fileName: String? = null,
		header: ByteArray? = null,
	): AssetFormat? {
		val declared = fromMimeType(mimeType) ?: fromFileName(fileName)
		val sniffed = fromHeader(header) ?: return declared
		// Every BMFF variant shares one container, and the generic brands ("isom", "mp42") are used
		// by video and audio alike, so only the declared type separates an .m4a from an .mp4.
		return if (sniffed == MP4 && declared in bmff) declared else sniffed
	}

	fun fromMimeType(mimeType: String?): AssetFormat? =
		byMimeType[mimeType?.substringBefore(';')?.trim()?.lowercase()]

	fun fromFileName(fileName: String?): AssetFormat? =
		byExtension[fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }]

	/** Identifies an asset from its leading bytes. Null when the signature isn't one we know. */
	fun fromHeader(header: ByteArray?): AssetFormat? {
		if (header == null || header.size < 4) return null
		return when {
			header.startsWith(0xFF, 0xD8, 0xFF) -> JPEG
			header.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
			header.matchesAt(0, "GIF8") -> GIF
			header.startsWith(0xFF, 0x0A) -> JXL
			header.startsWith(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20) -> JXL
			// TIFF byte-order marks, shared by every raw format built on TIFF (DNG, ARW, NEF).
			header.startsWith(0x49, 0x49, 0x2A, 0x00) -> TIFF
			header.startsWith(0x4D, 0x4D, 0x00, 0x2A) -> TIFF
			header.matchesAt(0, "fLaC") -> FLAC
			header.matchesAt(0, "ID3") -> MP3
			header.matchesAt(0, "RIFF") -> riffFormat(header)
			header.matchesAt(4, "ftyp") -> bmffFormat(header)
			header.isMpegAudioFrame() -> MP3
			header.looksLikeXml() -> SVG
			else -> null
		}
	}

	/** RIFF is a container: the form type at offset 8 says which of WebP, WAV or AVI it holds. */
	private fun riffFormat(header: ByteArray): AssetFormat? = when {
		header.matchesAt(8, "WEBP") -> WEBP
		header.matchesAt(8, "WAVE") -> WAV
		header.matchesAt(8, "AVI ") -> AVI
		else -> null
	}

	/** The major brand at offset 8 separates the BMFF variants that carry distinct parsers. */
	private fun bmffFormat(header: ByteArray): AssetFormat = when {
		header.matchesAt(8, "avif") || header.matchesAt(8, "avis") -> AVIF
		header.matchesAt(8, "mif1") || header.matchesAt(8, "msf1") -> HEIF
		header.matchesAt(8, "heic") || header.matchesAt(8, "heix") -> HEIC
		header.matchesAt(8, "hevc") || header.matchesAt(8, "hevx") -> HEIC
		header.matchesAt(8, "qt  ") -> MOV
		header.matchesAt(8, "M4A ") -> M4A
		header.matchesAt(8, "M4V ") -> M4V
		else -> MP4
	}
}

/**
 * Identifies [this] for the C2PA reader, preferring leading bytes over the declared type.
 *
 * [ImageSource.Path] is matched on its declared type and file extension only. Reading its head
 * would need file I/O, which this layer stays clear of. Every source the app builds today is
 * [ImageSource.Bytes], which carries its own header.
 */
fun ImageSource.resolveFormat(): AssetFormat? = AssetFormats.resolve(
	mimeType = mimeType,
	fileName = (this as? ImageSource.Path)?.path,
	header = (this as? ImageSource.Bytes)?.bytes?.let {
		it.copyOf(minOf(AssetFormats.HEADER_BYTES, it.size))
	},
)

/**
 * Whether the app's image loader can turn this format into a bitmap on API [sdkInt].
 *
 * Reading provenance and drawing a preview are independent capabilities. The reader parses TIFF,
 * raw and JPEG XL, which nothing in the app can decode, so those assets are still inspected and
 * simply shown without a preview rather than refused. SVG is decodable only because Coil is
 * configured with an SVG decoder, so this tracks that configuration rather than the platform alone.
 */
fun AssetFormat.isRenderableBy(sdkInt: Int): Boolean = when (token) {
	"image/jpeg", "image/png", "image/webp", "image/gif" -> true
	// Platform HEIF decoding predates our minSdk; AVIF only arrived later.
	"image/heic", "image/heif" -> true
	"image/avif" -> sdkInt >= SDK_AVIF
	// No platform decoder at any API level; supplied by coil-svg.
	"image/svg+xml" -> true
	else -> false
}

/** `Build.VERSION_CODES.S`, spelled out because this layer holds no `android.*` imports. */
private const val SDK_AVIF = 31

private fun ByteArray.startsWith(vararg bytes: Int): Boolean {
	if (size < bytes.size) return false
	return bytes.indices.all { this[it].toInt() and 0xFF == bytes[it] }
}

/** Compares [ascii] against this array at [offset] without needing a charset (KMP-clean). */
private fun ByteArray.matchesAt(offset: Int, ascii: String): Boolean {
	if (size < offset + ascii.length) return false
	return ascii.indices.all { this[offset + it].toInt() and 0xFF == ascii[it].code }
}

/** An MPEG audio frame sync: eleven set bits. Checked after JPEG and JXL, which also lead with FF. */
private fun ByteArray.isMpegAudioFrame(): Boolean =
	size >= 2 && this[0].toInt() and 0xFF == 0xFF && this[1].toInt() and 0xE0 == 0xE0

private fun ByteArray.looksLikeXml(): Boolean {
	var i = if (startsWith(0xEF, 0xBB, 0xBF)) 3 else 0
	while (i < size && this[i].toInt().toChar().isWhitespace()) i++
	return matchesAt(i, "<?xml") || matchesAt(i, "<svg")
}
