# Home, Search and Library

[User guide](index.md)

## Home depends on metadata

The Music tab renders the selected metadata provider's feed using Milkbeat's TV components. Each provider chooses its content and sections.

### YouTube Music

Mood filters, Quick picks, album/mix/playlist covers, artist portraits and music-video cards follow the YouTube Music feed. A signed-in account supplies personalized shelves; anonymous browsing is also supported.

![YouTube Music Home with Quick picks and a related shelf](images/home-youtube.png)

### Spotify

Spotify provides its Home shelves, recommendations, artists, albums and playlists. Select a separate audio provider for playback. Signing in makes the account's personalized feed and library available.

![Spotify Home with recommended stations and top mixes](images/home-spotify.png)

### Beatport

Genre filters, recommendations, charts and releases come from Beatport. Artist and label pages use the same catalog interface. Full audio depends on the account's streaming entitlement.

![Beatport Home with genre filters, recommendations and charts](images/home-beatport.png)

### SoundCloud Home and Library

SoundCloud Home offers **Discover** shelves supplied by the signed-in account and a separate
**Stream** activity feed. Shelves can contain mixes, stations, playlists, albums and artist portraits.
Library includes All, Playlists, Albums, **Artists**, Liked Songs and History. Artists means the
accounts you follow in SoundCloud; it uses SoundCloud's Following list without changing that list.
Opening a collection loads its tracks in order, including repeated occurrences. Private or removed
tracks may be unavailable. Metadata does not guarantee that your SoundCloud account can play the
full recording.

## Search

Music search uses the selected metadata provider. Video search uses the video provider. Filters and suggestions depend on that provider's supported features. Opening a result uses the catalog page appropriate to its type.

![Music search suggestions and a result example](images/search.jpg)

## Albums, playlists and artists

Collection pages show artwork, details and track lists, with Play and Shuffle actions. Artist pages can include top songs, albums, singles and related content. Each page remains tied to the provider it came from.

![Spotify station playlist rendered with Milkbeat collection controls](images/station-loaded.png)

![YouTube Music album page example](images/album.jpg)

![Artist header and top songs example](images/artist.jpg)

![Artist release and video shelves example](images/artist-shelves.jpg)

The search, album and artist examples above are retained captures from the earlier README; current provider Home and station captures show 0.8.9.

## Library

| Section | Contents |
| --- | --- |
| Folders | Configured local and SMB sources |
| Local library | Folder music indexed by artists, releases, playlists, genres, labels and years |
| History | Music and video activity recorded by the app |
| Watch later | Saved items for later playback |
| Playlists | App playlists and playlists from every enabled, signed-in metadata provider |
| Liked songs, inside Playlists | Music likes from the app and supported provider libraries |

There is one Playlists section. Liked videos are omitted from this TV library view. Other account-specific sections depend on the selected provider. Provider lists are paged; an unavailable provider does not hide the others, and failed pages can be retried.

Library → Folders browses the storage sources directly. **Local library** organizes their tagged
music into artists, releases, playlists, genres, labels and years. Selecting a track plays that
track alone; use a collection's **Play** or **Shuffle** button to queue its tracks. An enabled
YouTube Music plugin can seed [radio](playback.md#mixes) from the first song without changing
the local playback source.
