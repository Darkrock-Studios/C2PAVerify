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

`pdf_valid.pdf` is the negative case: our native library is built without the `pdf` feature, so PDF
must be refused rather than routed to another format's parser.

Not vendored: `dng_valid.dng` and `no_c2pa_042.dng` are ~18 MB each. Audio and video fixtures
(m4a, mp3, wav, flac, mov, mp4) are available upstream if that scope opens up.
