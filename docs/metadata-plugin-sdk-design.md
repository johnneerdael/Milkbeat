# Metadata plugin SDK and internal validation

Historical proposal: the current host and Spotify port use the `.mbplugin` SDK.
See [the September 30 validation](research/spotify-port-validation-2026-09-30.md)
for the implemented runtime, matching support and selected account model.

Status: phase 1 design proposal, 2026-09-27. The SDK, host and Spotify demo are
not implemented. The supplied Spotify HAR has been analyzed in the
[capture report](research/spotify-tv-har-2026-09-27.md). Runtime selection and the
report's remaining validation gaps are inputs to the final implementation plan.

## Objective

Let third-party authors build independently installable MusicViz metadata
plugins that control catalog content and compose native music pages. Provide
an SDK, development tools and an internal Spotify demo that proves this works
through the same public interfaces available to any plugin author.

Phase 1 delivers metadata, authentication and UI composition only. It does not
change the playback entity or implement audio plugins, matching, stream
resolution, queue behavior, downloading or playback of external metadata tracks.
Those capabilities belong to phase 2.

## Deliverables

| Deliverable | Responsibility |
| --- | --- |
| Versioned SDK contract | Metadata entities, page blocks, layouts, elements, actions, capabilities, authentication states, pagination and typed errors |
| Plugin author API | Typed entry points for metadata requests and approved host services, with cancellation and lifecycle rules |
| Package tools | Validate manifests, check compatibility, assemble a plugin package and verify its contents |
| Author template | A minimal runnable metadata plugin with synthetic fixtures and no provider credentials |
| Local development harness | Run plugin requests with fake HTTP/storage/auth services and inspect returned pages without a live account |
| Contract tests | Check required surfaces, schema validity, capability enforcement, pagination, account separation and lifecycle behavior |
| Native host renderer | Render the SDK's supported page elements consistently on TV |
| Internal Spotify demo | Consume Spotify account metadata using contracts verified from the HARs and public TV client research |
| Documentation | Setup, build/install, API reference, authentication, page composition, testing, updates and compatibility examples |

The canonical UI vocabulary is defined in
[Metadata plugin UI contract](metadata-plugin-ui-contract.md). Reuse that schema
in the SDK, fixture validators and host. Do not maintain divergent copies of its
types or hand-edit independently generated bindings.

## Boundaries

```text
Plugin author
    -> SDK + template + fixtures + package validation
    -> separately installed plugin package

MusicViz plugin host
    -> compatibility and lifecycle management
    -> isolated execution with restricted service access
    -> typed metadata pages
    -> native UI renderer
```

Keep the SDK contract independent of Android UI classes and MusicViz playback
classes. It must not expose Activity, Context, ViewModel, Compose functions,
database handles, player commands, `MusicTrack.videoId`, or media-session objects.
The host adapts SDK metadata to its browsing UI without routing external IDs into
existing playback code.

An SDK is not an interpreter or security boundary. Use a maintained execution
runtime or Android's application isolation. Do not write a language runtime,
cryptography implementation or custom networking stack for this project.

## Runtime decision before implementation

The user selected arbitrary third-party plugins. In-process Zipline alone does
not provide the required isolation; its own documentation explicitly limits it
to trusted code. See the evidence and alternatives in the
[research note](spotify-metadata-research.md#runtime-choice-for-arbitrary-third-party-code).

Evaluate AndroidX JavaScriptEngine on the target TVs for script-package hosting.
Check runtime availability, host-request bridging, termination behavior, memory
limits and cross-plugin account separation. Its isolates are not automatically
strong security boundaries from each other. A timeout must terminate work, not
only stop awaiting its result.

If that route cannot satisfy target-device and isolation requirements, evaluate
a separate Android plugin APK with a narrow bound-service protocol. Select one
transport for v1; do not build multiple runtimes preemptively. The selected
transport determines the first author SDK language and packaging toolchain.
The metadata/UI protocol and author-facing behavior remain the design anchor.

## Package and lifecycle contract

The manifest includes stable plugin identity, publisher identity, version,
supported SDK/schema range, execution entry point, required UI types, metadata
capabilities, authentication requirements and requested host permissions.

Support install, inspect, enable, authenticate, select, update, disable and
uninstall. A plugin can be installed but not selectable until its required
authentication succeeds. Providers supporting anonymous metadata declare that
explicitly. Uninstalling a plugin must not modify playback history or queues.

Use platform/library package and signature mechanisms. Verify publisher
continuity for updates; a signature establishes origin, not that arbitrary code
is safe. Validate package paths and resource limits before execution. Refuse
incompatible required SDK/UI versions with an actionable compatibility state.

Install an update atomically, retain a last-known-working package, and stop old
plugin work before switching versions. Tag outstanding requests with plugin
version and account-session generation so a late response cannot write new
state. Rollback behavior must account for plugin storage-schema compatibility;
rolling code back alone must not corrupt credentials or persisted data.

Keep request execution lazy and bounded. Disable/uninstall/logout cancels the
relevant work and removes its active UI data. Plugin crashes and timeouts must
not crash MusicViz or issue playback commands.

## Host services and account ownership

For script plugins, expose scoped HTTP, plugin/account storage, authentication
presentation, clock and diagnostic services as needed. Apply destination and
redirect policy, deadlines, payload limits and cancellation at the host boundary.
Do not let plugins read other accounts, other plugins, MusicViz private files,
or arbitrary Android services. For APK plugins, map ownership to Android's
application boundary and limit the IPC surface; do not claim the host controls
an independent APK's own network access.

Authentication presentation supports a typed device-code challenge: verification
URI, user code and expiry. MusicViz renders the QR and status; the plugin handles
its provider's request semantics through the selected SDK services. Preserve an
extension point for a supported browser sign-in flow without making every
provider implement it in v1.

Keep credentials out of plugin packages, fixtures, diagnostics and UI payloads.
Use established platform/library storage protection. Scope stored sessions to
plugin and account, coordinate refresh as a single in-flight operation, and
prevent a late refresh from restoring a session after logout. The new capture
establishes pairing and five-second polling, with access and refresh tokens on
success. It does not contain a refresh-token exchange; test that separately.

Represent unauthenticated, authenticating, authenticated and expired states
explicitly. Offline or transient server failure is not automatically session
expiry. Authentication failure must not erase an unrelated provider's session.

## Developer experience

An author should be able to:

1. Start from a small metadata plugin template using the supported SDK version.
2. Declare artists, albums and playlists for Search and Library, with track
   metadata and optional capabilities such as podcasts.
3. Implement account and metadata entry points, returning typed pages and actions.
4. Preview varied page fixtures and inspect validation errors before installing.
5. Run deterministic tests with supplied fake host services.
6. Build a validated package and install it into a development MusicViz build.
7. Inspect scoped diagnostics, update the plugin and test failure/rollback paths.

The template uses synthetic data. Its examples cover mixed cards, multi-column
rows, an artist banner, a track table, section-local filters and an About block.
Document errors with page/block/item IDs so the author can locate an invalid
element. Keep implementation diagnostics out of ordinary product flows.

## Spotify demo and HAR workflow

The first real plugin is an internal Spotify metadata demo. It must not get
private host APIs, direct app database access or Spotify-specific renderer code.
If it cannot express a required screen through the SDK, fix the general contract
deliberately and add a fixture demonstrating the missing requirement.

Use wiretrail on the user-supplied full capture to build an operation inventory:

- Pairing/login, account identification and session refresh when captured.
- Home/recents, discovery pages and paging of both sections and their contents.
- Search, suggestions where available, and artist/album/playlist results.
- Artist, album and playlist details, including optional descriptive metadata.
- Account library, saved state, and save/remove operations.
- Optional podcast operations only if the demo declares that capability.

The first recording establishes login, home paging, library filters, artist,
album and playlist reads, and saved-state reads. The
[targeted 04:43 capture](research/spotify-tv-har-2026-09-28-0443.md) additionally
establishes browse and track save/unsave request paths. Its Search operation
returns `{}`, and neither capture contains a refresh exchange. The demo must
batch saved-status lookups for
new IDs instead of repeatedly sending all accumulated playlist IDs: the capture
contains two HTTP 431 failures on growing requests. See the capture report for
entry IDs and a bounded-batch regression scenario. YouTube's plugin must omit
podcasts across all surfaces even though the SDK supports that optional kind.

For each operation, record stable HAR entry IDs, method, endpoint, variables,
required headers by name, response shape, pagination behavior and observed errors.
Separate captured evidence, public-bundle evidence and untested assumptions.
Do not infer an endpoint's existence from a screenshot or treat a missing body
as proof that the provider has no such capability.

Convert selected responses into minimal sanitized fixtures, retaining semantic
structure while removing credentials and unnecessary personal account details.
Verify sanitization before committing. No real access/refresh tokens, cookies,
passwords or active pairing codes belong in fixtures. Raw HARs remain local in
the ignored capture directory; fixtures are not replayable account sessions.

Validate in three layers:

| Layer | Evidence produced |
| --- | --- |
| Offline fixture tests | Request construction, response mapping, paging, UI schema and failure handling |
| Native renderer tests | All supported block/element compositions, focus, filters and detail navigation |
| Authorized live account test on TV | Actual pairing, account metadata reads, session lifecycle and supported account mutations |

Offline success does not prove live login or endpoint compatibility. Live metadata
success does not prove playback, which is outside this phase. Report the evidence
and remaining gaps separately.

The internal demo may ship as a development artifact while the SDK is stabilized.
A public marketplace, automatic third-party publishing service and audio plugin
ecosystem are not required to validate phase 1.

## Release gates

- Build the Spotify demo through the public SDK and install it separately.
- Exercise required Search/Library kinds and all screenshot-derived UI elements
  with appropriate fixtures; keep optional capabilities hidden when absent.
- Verify process death, timeout, excessive payload, malformed response,
  incompatible version, update failure and rollback.
- Verify plugin/account storage isolation and denial of undeclared host access.
- Verify cancellation and stale-response rejection during filter changes,
  provider switches, logout, disable, uninstall and update.
- Test TV D-pad navigation, focus restoration, pagination and lifecycle suspension.
- Confirm no changes to playback entities and no external plugin calls into
  play, queue, matching, stream, cache or download paths.
- Measure runtime startup cost, metadata request counts and idle work on a target
  device before making performance claims.

These gates define what robustness must mean in observable behavior. They are
implementation requirements, not checks already passed by this design document.

## Live-set recording descriptions

`TrackDescriptor.description` is optional recording metadata. Providers may return a recording description with an audio-match candidate so the host can confirm a live festival set’s recording day. It defaults to null for existing providers and is retained in saved matches. Matching uses recording dates tied to the festival, not upload dates or unrelated dates in tracklists. Tomorrowland/TML is the supported festival alias; the performer, day and full-set duration remain required.
