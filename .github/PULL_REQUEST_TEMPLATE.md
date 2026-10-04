## Summary

<!-- Explain what changed and why. Focus on observable behavior and important implementation decisions. -->

## Release notes

<!--
Required: the "PR release notes" check fails without this section. It is published as this PR's
entry in the next release, so write factual, public, user-facing changes, one bullet each, e.g.
- Plugins update automatically in the background; updates that ask for new permissions wait for review.
Sub-headings such as ### Fixed or ### New are kept. If nothing changes for viewers, write
Internal: followed by what actually changed (e.g. Internal: Pin the Gradle wrapper to 9.1).
Leave out test results, screenshots, install links and plugin codes: the release workflow appends the
visualizer engine, install instructions, plugin downloader codes and the full changelog link itself.
-->

-

## Related issue

<!-- Use "Closes #123" when this PR should close an issue. -->

## Change type

- [ ] Bug fix
- [ ] Feature
- [ ] Refactor or maintenance
- [ ] Build, packaging, or CI
- [ ] Documentation

## Validation

<!-- List the exact checks you ran and their results. Mark non-applicable checks as such instead of claiming they passed. -->

- [ ] `./gradlew :app:assembleGithubDebug`
- [ ] `./gradlew :app:testGithubDebugUnitTest`
- [ ] I ran any additional flavor-specific build or test tasks affected by this change.
- [ ] I manually tested the affected behavior on an Android device or emulator.

**Test device and Android version:**

## Screenshots or recordings

<!-- Required for visible UI changes. Remove this section when it does not apply. -->

## Risk and compatibility

<!-- Note database or preference migrations, permissions, network behavior, playback impact, background work, battery impact, and known limitations. -->

- [ ] The change does not introduce secrets, private data, or unexpected telemetry.
- [ ] New user-facing text uses Android string resources.
- [ ] Dependency and lockfile changes are intentional and limited to this PR.
- [ ] Room schema changes include the required version bump and migration, or this PR does not change the Room schema.
- [ ] Breaking changes and upgrade steps are clearly documented.
- [ ] The `Release notes` section matches the final change and contains no placeholders or test logs.
