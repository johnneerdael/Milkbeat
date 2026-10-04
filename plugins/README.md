# Public plugin API contract

This workspace keeps the TypeScript SDK and JSON schema aligned with Milkbeat's native plugin API. The app's generic plugin runtime, installation, updates, sign-in and download-code support remain in the public app repository.

```sh
npm ci
npm run check
```

Compiled third-party packages are not kept in git; they download only from Buzzheavier. The publisher verifies each package against its Buzzheavier account listing before registering it, and supplies the publication metadata (`published.json`) and the encrypted download catalog, which the app CI checks against each other.

Third-party plugin packages are distributed separately from Milkbeat. Milkbeat releases contain the app APKs and checksums; the app's README lists optional third-party downloader codes.
