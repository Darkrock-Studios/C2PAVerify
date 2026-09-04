# Upstream format fixtures

Vendored from [c2pa-org/public-testfiles](https://github.com/c2pa-org/public-testfiles), branch
`darrellkindred/valtest`, path `contrib/google/assets/`. Licensed CC BY-SA 4.0 per the upstream
LICENSE. Refreshed 2026-08-28.

One signed and one unsigned file per still-image format, so a format that loses its parser fails
`C2paReaderCaptureTest` instead of reaching users as a corrupt-file report.

| Format | Signed | Unsigned |
| ------ | ------ | -------- |
| PNG    | `png_valid.png`   | `no_c2pa_006.png`  |
| WebP   | `webp_valid.webp` | `no_c2pa_040.webp` |
| GIF    | `gif_valid.gif`   | `no_c2pa_041.gif`  |
| TIFF   | `tiff_valid.tiff` | `no_c2pa_018.tiff` |
| AVIF   | `avif_valid.avif` | `no_c2pa_008.avif` |
| PDF    | `pdf_valid.pdf`   | n/a                |

The signed files are signed by upstream's *test* CA (`certs/root_ca1_cert.pem`) with certificates
valid around 2001, and their descriptors pin `validationTime: 2001-06-01`. Read today our reader
returns `validation_state: Invalid` with `signingCredential.expired`,
`signingCredential.untrusted` and `claimSignature.mismatch`, while the hard binding still reports
`assertion.dataHash.match`. So assert only that they parse: a trust verdict here measures the
clock and the trust list, not format handling.

`pdf_valid.pdf` proves PDF reaches its own parser. The `pdf` feature *is* compiled into our native
library, and a device read returns `assertion.dataHash.match`: the hard binding holds, exactly as
for a still. Only writing is unimplemented upstream, which costs a verifier nothing.

There is no tampered PDF upstream, so `C2paReaderCaptureTest` makes one: it flips a character of
the document title in object 1, which sits before the hash exclusion at bytes 6191..7632 and leaves
every offset and the xref intact, and asserts the read comes back `assertion.dataHash.mismatch`.

Not vendored: `dng_valid.dng` and `no_c2pa_042.dng` are ~18 MB each. Audio fixtures (m4a, mp3, wav,
flac) are available upstream if that scope opens up.

The MP4 fixture is fetched, not vendored. `UpstreamVideo` downloads
`legacy/1.4/video/mp4/truepic-20230212-zoetrope.mp4` (15.4 MB, the only C2PA-signed MP4 upstream
publishes) from `c2pa-org/public-testfiles` on `main` and caches it in the app's files dir. A test
run reinstalls the app, which wipes that, so it is fetched roughly once per run. 15 MB is a
permanent cost to the repo for one test file; the tests skip rather than fail when the download
cannot be made.
