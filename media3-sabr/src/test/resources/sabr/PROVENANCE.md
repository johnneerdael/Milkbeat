# Container fixture provenance

Generate these small synthetic media fixtures with the maintained FFmpeg encoders and muxers. They contain no downloaded media, account data or signed URLs.

```sh
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=4 -t 6 -an -c:v libx264 -g 8 -pix_fmt yuv420p -movflags frag_keyframe+empty_moov+default_base_moof+global_sidx -metadata title="SABR container fixture" fragmented.mp4
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=4 -t 4.5 -an -c:v libx264 -bf 0 -g 8 -sc_threshold 0 -pix_fmt yuv420p -movflags frag_keyframe+empty_moov+default_base_moof+global_sidx -metadata title="SABR short final segment fixture" fragmented-short.mp4
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000 -t 6 -vn -c:a libopus -cluster_time_limit 2000 audio.webm
```

`fragmented.mp4` has 24 AVC samples and exercises Media3's indexed fragmented MP4 and metadata paths. `fragmented-short.mp4` has 18 samples split into 2 s, 2 s and 500 ms fragments for sequential loads and seek. `audio.webm` contains Opus audio split into WebM clusters. Tests wrap these containers in upstream generated SABR control messages, split their media across arbitrary UMP boundaries and limit network reads to seven bytes. The binary fixtures are independent of the Java protocol/extractor implementation.

For the initialization-handshake regression, generate a two-second stereo AAC fragmented MP4 with:

```sh
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000 -t 2 -vn -c:a aac -ac 2 -movflags frag_keyframe+empty_moov+default_base_moof audio-fragmented.mp4
```

Split this independent muxed container at its first `moof`: the initialization POST receives `ftyp`/`moov` and format metadata; the subsequent selected-format POST receives media fragments without repeating metadata. Both traverse the maintained Media3 chunk/extractor pipeline.
