# Maintain the user guide

Edit the Markdown in `docs/user-guide/`. The same files render on GitHub and in the
GitHub Pages manual. MkDocs supplies navigation, search and Markdown rendering;
the app's build and runtime dependencies are unchanged.

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
Before the first deployment, set the repository's **Settings → Pages → Source**
to **GitHub Actions**. The site address is
`https://johnneerdael.github.io/Milkbeat/`.

## Screenshots

Keep raw TV captures under `docs/user-guide/images/`, with descriptive names and
alt text. Check for passwords, sign-in QR codes and account details before adding
a capture. Explain controls next to the relevant image instead of placing every
screenshot in an undifferentiated gallery.

The guide was checked against 0.8.9 on an Ugoos AM6 on 2 October 2026. Its Home,
provider, settings, SMB setup, player and queue captures come from that session.
The album, artist, search and music-video examples were retained from the
existing README captures. Replace an example when that surface changes.
