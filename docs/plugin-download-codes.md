# Maintain plugin download codes

The app accepts registered three-digit codes in Settings → Plugins and in an
add-plugin deep link's `url` parameter. Full download URLs remain supported.

<!-- plugin-codes:maintainer -->
| Code | Package |
| --- | --- |
| 102 | Beatport |
| 772 | Spotify |
| 416 | YouTube Music |
| 393 | Beatport, private publisher |
| 981 | Spotify, private publisher |
| 494 | YouTube Music, private publisher |
| 089 | SoundCloud, private publisher |
<!-- /plugin-codes -->

## Register another URL

From the repository root, run:

```sh
node tools/plugin-download-codes.mjs add dev.example.plugin "Example" https://example.com/example.mbplugin
node tools/plugin-download-codes.mjs list
node --test tools/plugin-download-codes.test.mjs
```

The tool normalizes the URL and derives a candidate from SHA-256. If that slot
is taken, it chooses the next unused slot. The committed catalog preserves
existing assignments; adding the same URL for the same plugin is idempotent.
Do not regenerate the catalog from scratch or delete assigned entries.

The namespace contains **1,000 codes, 000–999**. Registration fails when it is
full. An exclusive file lock prevents concurrent local commands from overwriting
each other's allocations, and a temporary file plus rename prevents partial
catalog writes. Pull main before registering entries and resolve competing
branch changes by registering against the latest catalog.

Commit `app/src/main/assets/plugin-download-catalog.json` with the change.
The app's next build delivers new entries; existing installations need an app
update to recognize them. A code maps to a URL, not to an automatically updated
plugin version. Changing hosting URLs requires a new registration.

## URL protection

The catalog uses AES-256-GCM with a random nonce and an authenticated format
label. Node's standard crypto API seals it; the app opens it through its existing
`SyncCrypto` wrapper over the platform cipher. The key is bundled intentionally:
this hides URLs from casual text inspection, and does not make them confidential
from someone who can inspect or run the app. Download URLs are also observable
when used and remain installation provenance in the app's private registry.

## Buzzheavier downloads

The host fetches the file page, reads its signed `hx-get` token with jsoup, requests
that endpoint with the HTMX headers, and follows `HX-Redirect` to the CDN. The
download session has its own short-lived cookies. Token requests stay on the
file site's origin; Buzzheavier redirects require HTTPS on Buzzheavier's own
domain or subdomains. Redirects, page size, package size and request time are
bounded, and cancellation cancels the active OkHttp call.

The package still passes the existing signature/manifest checks and install
review. For a registered code, its manifest ID must also match the catalog's
expected plugin ID. A browser challenge or missing link is reported as a failure;
the host does not automate interactive verification.

The protocol was checked against the local Buzzheavier-Keeper and BuzzInstaller
references, then verified by downloading all three signed packages on an Ugoos
AM6. jsoup was added because the app's resolved compile classpath contained no
HTML parser. The existing OkHttp client and crypto implementations are reused.

## Third-party plugin downloads

Milkbeat app releases contain APKs and checksums. Third-party plugins are installed separately through a downloader code or URL. The public API/SDK contract and the generic runtime, installer, updater and downloader-code support remain available. The README lists third-party codes; the encrypted catalog resolves them to download URLs.
