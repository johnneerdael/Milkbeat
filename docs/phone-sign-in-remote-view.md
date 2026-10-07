# Phone sign-in viewer

This is the default phone interface for every plugin `webLogin` method in
Milkbeat 0.8.0. Provider browsing stays in the native catalog renderer. Plugins
declare their existing login URL, completion condition and extraction; the host
provides the responsive viewport, pairing, frames, touch and keyboard controls.
No provider-specific phone page or duplicate form controls are required.

Render the provider's sign-in in the isolated TV WebView at a 360 × 720 dp
viewport. Scale that viewport into the TV's sign-in pane with Compose's layout
and graphics primitives. Preserve the provider's responsive design and origin.

Serve the phone viewer through the existing one-session LAN server. The phone
requests encrypted JPEG frames, displays them as a live image and sends
normalized touch gestures through the existing AES-GCM channel. Native
`MotionEvent` dispatch reaches embedded cross-origin frames. Native input
connections type into the focused browser editor. The phone has one keyboard
bar and no copied provider buttons or form fields.

Use `PixelCopy` for hardware-rendered WebView pixels and the layout's transformed
window bounds. Limit the longest frame edge to 960 pixels and capture at most
twice a second. Require authenticated, replay-protected frame requests, bind
them to the paired phone, and serialize the phone's requests to preserve
sequence order. Bind encrypted frame replies to the authenticated request
sequence and response type. Bound network requests to five seconds so a stalled
preview cannot hold the input queue. Capture only while the sign-in view is visible. Stop phone
frame polling when its document or viewer is hidden and on completion.

Use the existing `SyncCrypto` and pinned noble-ciphers implementation. Keep
frames in memory, return encrypted data with `Cache-Control: no-store`, and
release bitmap/blob resources after use. Do not persist screenshots of the
real account sign-in or log credentials, channel keys or frame contents.

Add optional `WebLoginMethod.pageScript` for signed providers that need a page
compatibility adjustment. Evaluate it after a document finishes loading; leave
the default empty. Spotify's setup script is restricted to
`accounts.spotify.com` and relaxes its collapsed height/overflow chain. Keep
this provider knowledge in the plugin.

Validate encrypted transport, peer binding, replay rejection, input bounds,
capture throttling, native screenshots and trusted input inside a sandboxed
iframe. Validate the real phone viewer and responsive Spotify page on the TV
before claiming account login works. Verification challenges are completed by
the person signing in.

## Device-code alternative

API 5 plugins can use device-code pairing instead of this remote WebView. The TV shows a provider
activation address, QR code and short code; approval happens directly on the provider’s site.
The native pairing controller requires no LAN viewer, follows the provider’s polling cadence only
while resumed, and cancels on navigation or pause. SoundCloud uses this method. The existing web
viewer remains available for providers that declare `webLogin`.
