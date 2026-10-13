package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThumbnailUrlResolverTest {
    @Test
    fun `normalizes jpg thumbnail variants to hq720`() {
        val result =
            ThumbnailUrlResolver.normalizeVideoThumbnail(
                "abc123",
                "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
            )

        assertThat(result).isEqualTo("https://i.ytimg.com/vi/abc123/hq720.jpg")
    }

    @Test
    fun `normalizes query and webp variants to hq720`() {
        val result =
            ThumbnailUrlResolver.normalizeVideoThumbnail(
                "fallback",
                "https://i.ytimg.com/vi_webp/abc123/mqdefault.webp?sqp=abc",
            )

        assertThat(result).isEqualTo("https://i.ytimg.com/vi/abc123/hq720.jpg")
    }

    @Test
    fun `keeps non YouTube thumbnails unchanged`() {
        val raw = "https://example.com/thumb.jpg"

        assertThat(ThumbnailUrlResolver.normalizeVideoThumbnail("abc123", raw)).isEqualTo(raw)
    }

    @Test
    fun `builds fallback when raw url is blank`() {
        assertThat(ThumbnailUrlResolver.normalizeVideoThumbnail("abc123", ""))
            .isEqualTo("https://i.ytimg.com/vi/abc123/hq720.jpg")
    }

    @Test
    fun `card candidates never request maxresdefault`() {
        val candidates = ThumbnailUrlResolver.youtubeThumbnailCandidates(VIDEO_ID)

        assertThat(candidates)
            .containsExactly(
                "https://i.ytimg.com/vi/$VIDEO_ID/hq720.jpg",
                "https://i.ytimg.com/vi/$VIDEO_ID/hqdefault.jpg",
            ).inOrder()
    }

    @Test
    fun `card candidates are empty for a blank video id`() {
        assertThat(ThumbnailUrlResolver.youtubeThumbnailCandidates("   ")).isEmpty()
    }

    @Test
    fun `a stored maxres url is downgraded to card tiers`() {
        val stored = "https://i.ytimg.com/vi/$VIDEO_ID/maxresdefault.jpg"

        val candidates = ThumbnailUrlResolver.resolveVideoThumbnailCandidates(VIDEO_ID, stored)

        assertThat(candidates).doesNotContain(stored)
        assertThat(candidates.first()).isEqualTo("https://i.ytimg.com/vi/$VIDEO_ID/hq720.jpg")
    }

    @Test
    fun `a non youtube thumbnail is kept ahead of the youtube fallbacks`() {
        val raw = "https://example.com/custom/art.png"

        val candidates = ThumbnailUrlResolver.resolveVideoThumbnailCandidates(VIDEO_ID, raw)

        assertThat(candidates.first()).isEqualTo(raw)
        assertThat(candidates).hasSize(3)
    }

    @Test
    fun `candidates recover the video id from the raw url`() {
        val raw = "https://i.ytimg.com/vi/$OTHER_ID/hqdefault.jpg"

        val candidates = ThumbnailUrlResolver.resolveVideoThumbnailCandidates("", raw)

        assertThat(candidates)
            .containsExactly(
                "https://i.ytimg.com/vi/$OTHER_ID/hq720.jpg",
                "https://i.ytimg.com/vi/$OTHER_ID/hqdefault.jpg",
            ).inOrder()
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        const val OTHER_ID = "aBcDeFgHiJk"
    }

    @Test
    fun `SoundCloud artwork is sized for its surface, largest for the player`() {
        val cover = "https://i1.sndcdn.com/artworks-8YHCyFdWzlsNZyj7-phesTg-large.jpg"

        assertThat(ThumbnailUrlResolver.resolveMusicThumbnail("101", cover, 1080))
            .isEqualTo("https://i1.sndcdn.com/artworks-8YHCyFdWzlsNZyj7-phesTg-t3000x3000.jpg")
        assertThat(ThumbnailUrlResolver.resolveMusicThumbnail("101", cover.replace("-large", "-t3000x3000"), 256))
            .isEqualTo("https://i1.sndcdn.com/artworks-8YHCyFdWzlsNZyj7-phesTg-t300x300.jpg")
        assertThat(ThumbnailUrlResolver.resolveMusicThumbnail("101", "https://i1.sndcdn.com/avatars-a1-t500x500.png", 400))
            .isEqualTo("https://i1.sndcdn.com/avatars-a1-t500x500.png")
    }

    @Test
    fun `unsized SoundCloud and other hosts keep their url`() {
        val unsized = "https://i1.sndcdn.com/cover.jpg"
        val elsewhere = "https://cdn.example/artworks-a1-large.jpg"

        assertThat(ThumbnailUrlResolver.resolveMusicThumbnail("101", unsized, 1080)).isEqualTo(unsized)
        assertThat(ThumbnailUrlResolver.resolveMusicThumbnail("101", elsewhere, 1080)).isEqualTo(elsewhere)
    }
}
