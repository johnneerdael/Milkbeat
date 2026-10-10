# Maintain the user guide

Edit the Markdown in `docs/user-guide/`. The site uses [Material for MkDocs](https://squidfunk.github.io/mkdocs-material/)
(pinned in `docs/site-requirements.txt`, with MkDocs 1.6.1) and the Milkbeat palette in
`docs/user-guide/stylesheets/milkbeat.css`: top tabs, card grids, content tabs and a hero home page,
in the same style as the [ProjectM TV guide](https://johnneerdael.github.io/ProjectM-TV/). Pages use
Material's Markdown extensions (`grid cards`, `=== "Tab"` blocks, `md_in_html` screenshot pairs in
`<div class="mb-pair" markdown>`), so they read best on the Pages site rather than on GitHub.
The app's build and runtime dependencies are unchanged. Keep the CSS free of gradients, blur
and glow, as the app's design rules require.

## Preview locally

From the repository root:

```sh
python3 -m venv debug/docs-env
debug/docs-env/bin/pip install -r docs/site-requirements.txt
debug/docs-env/bin/mkdocs serve
```

Open the address printed by MkDocs. Its `/Milkbeat/` path matches the Pages site.

## Check and publish

```sh
debug/docs-env/bin/mkdocs build --strict
```

The reusable User guide workflow builds reviewed pull requests targeting `main`. The full main
pipeline calls it to build and publish the guide after merge. Unreviewed and non-main PRs skip the
site build; the review controller checks readiness before dispatching validation. Manual and main
pipeline deployments share one Pages lock; a build of an older main commit is skipped after
acquiring it, so it cannot replace the newer guide.
Reusable callers must allow the deployment job's Pages/OIDC permissions even when `pr_build`
skips deployment: GitHub validates the whole workflow before evaluating that condition. The
guide build job explicitly limits its token to `contents: read`, so building PR source does not
receive Pages or OIDC write access. Deployment remains disabled for PR validation.
Before the first deployment, set the repository's **Settings → Pages → Source**
to **GitHub Actions**. The site address is
`https://johnneerdael.github.io/Milkbeat/`.

## Screenshots

Keep TV captures under `docs/user-guide/images/` at 1920×1080, with descriptive names and
alt text. PNG suits menus and settings; JPEG (quality about 86) suits visualizer, video and
artwork captures. Check for passwords, sign-in QR codes and account details before adding a
capture, and blur account names, avatars and usernames (the plugin details, YouTube Music's
Listen again shelf and SMB usernames in the current set). Explain controls next to the relevant
image instead of placing every screenshot in an undifferentiated gallery.

The current set was captured over `adb` from Milkbeat 0.9.55 on a 4K Smart TV Pro box
(Android 14, 1920×1080 UI) on 8 October 2026. Retained older captures: the local storage picker,
the SFTP server-key editor, the SoundCloud pairing example (with a fake code), the diagnostics
line and two visualizer effect examples, plus the preview app's controlled emulator captures.
The audio priority page (`settings-audio-priority.png`) is an emulator capture of the debug build
on 10 October 2026, cropped above the emulator's navigation bar.
Replace an example when that surface changes. Delete images that no page references.
