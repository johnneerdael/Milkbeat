# SABR transport provenance

Adapt SmartTube's `exoplayer-amzn-2.10.6/library/sabr` from local revision `bf9cd3142`, preserving source behavior and notices. The transport is adapted to the existing Media3 1.11.0 engine under `nl.neerdael.milkbeat.sabr`; no second ExoPlayer engine or SmartTube application singleton is introduced. Provider endpoints/authentication remain in the private `youtube-video` plugin. Protobuf definitions are generated with the maintained protobuf toolchain, not handwritten codecs.

The maintainer explicitly requests these SmartTube playback pieces, including SABR. Media3's resolved artifacts have no equivalent SABR module. Protocol, format selection, token/CDN recovery and reload/seek behavior require fixture and native verification before claiming support.

## Verified adaptations

- Generate upstream protobuf schemas in `:media3-sabr-protocol` with protoc and Java-lite 4.35.1. The protobuf Gradle plugin 0.9.5 runs in a JVM library because it does not support the current Android library DSL.
- Use Media3's `ExtractorInput`; its methods no longer declare `InterruptedException`.
- Media3 1.11.0 has no `Format.lastModified` and its `Metadata.Entry` no longer extends `Parcelable`. Preserve the unsigned last-modified bits, xTags and audio-track identity in immutable format metadata.
- Replace SharedUtils equality and first-match helpers with Java equivalents, and use Android's URI implementation for query handling.
- Remove verbose upstream parser logging that prints signed URLs, opaque reload tokens or SABR context messages. Retain protection-status and reload events for the playback layer.
- Reject truncated UMP integers and headers that exceed the signed integer range before constructing a part. Preserve the upstream UMP encoding, which differs from protobuf varints.

The current parser checkpoint is not yet wired into the app. Media-source, chunk/extractor adapters, refresh handling and native playback verification remain required before claiming SABR support.
