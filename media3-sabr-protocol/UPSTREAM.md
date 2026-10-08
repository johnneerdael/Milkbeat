# SABR protocol provenance

These schemas come from the local SmartTube checkout at revision `bf9cd3142`, directory `exoplayer-amzn-2.10.6/library/sabr/src/main/proto`. Preserve field numbers, scalar types, presence and enum values. The Java package changes to `nl.neerdael.milkbeat.sabr.protos` for the Media3 integration.

Generate maintained Java-lite classes with protoc 4.35.1; generated build output is not committed. The JVM library avoids the protobuf Gradle plugin's incompatibility with AGP 9.3's Android library DSL. Run `./gradlew :media3-sabr-protocol:generateProto :media3-sabr:testDebugUnitTest` to generate and verify the consuming parser.
