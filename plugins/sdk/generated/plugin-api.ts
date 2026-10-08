/* Generated from plugin-api by scripts/generate-types.mjs. Do not edit. */

export type MetadataSurface = 'HOME' | 'SEARCH' | 'SUGGEST' | 'ENTITY' | 'TRACKS' | 'LIBRARY' | 'RADIO';
export type EntityKind =
  'TRACK' | 'MUSIC_VIDEO' | 'ALBUM' | 'PLAYLIST' | 'ARTIST' | 'PROFILE' | 'MIX' | 'RADIO' | 'VIDEO' | 'CHANNEL';
export type AudioDelivery = 'PROGRESSIVE' | 'HLS';
export type VideoSurface = 'SEARCH' | 'SUGGEST' | 'CHANNEL' | 'PLAYLIST' | 'RELATED' | 'COMMENTS' | 'LIVE_CHAT';
export type SignInMethod = SignInMethodDeviceCode | SignInMethodWebLogin;
export type SettingType = 'TOGGLE' | 'CHOICE' | 'TEXT';
export type PageBlock = PageBlockCollection | PageBlockHeader;
export type CollectionLayout = 'HORIZONTAL_SHELF' | 'MULTI_COLUMN_LIST' | 'TRACK_TABLE';
export type ItemView = 'COVER_CARD' | 'LANDSCAPE_CARD' | 'ARTIST_PORTRAIT' | 'TRACK_ROW';
export type HeaderStyle = 'PORTRAIT' | 'COVER';
export type PersonalCollectionKind = 'OWNED_PLAYLIST' | 'LIKED_SONGS';
export type PrivatePlaylistImportMode = 'REPLACE' | 'ENSURE' | 'APPEND';
export type PrivatePlaylistImportPhase = 'PREPARING' | 'WRITING' | 'VERIFYING';
export type AudioQuality = 'AUTO' | 'HIGH' | 'MEDIUM' | 'LOW';
export type ServerAbrFailure = 'ATTESTATION_REQUIRED' | 'PLAYBACK_CONTEXT_RELOAD' | 'NO_PROGRESS' | 'URL_EXPIRED';
export type FormatType = 'AUDIO' | 'VIDEO';
export type AudioDrmScheme = 'WIDEVINE';
export type AudioMatchStrategy = 'SONGS' | 'ALTERNATE_SONGS' | 'VIDEOS';
export type PluginErrorCode =
  | 'NOT_FOUND'
  | 'UNAVAILABLE'
  | 'SIGN_IN_REQUIRED'
  | 'SIGN_IN_EXPIRED'
  | 'RATE_LIMITED'
  | 'NETWORK'
  | 'TIMEOUT'
  | 'UNSUPPORTED'
  | 'INTERNAL';
export type VideoKind = 'VOD' | 'LIVE' | 'UPCOMING';
export type DeviceCodeStatus = 'pending' | 'signedIn' | 'expired' | 'denied';
export type ProviderAccount = ProviderAccountAnonymous | ProviderAccountExpired | ProviderAccountSignedIn;
export type HttpBodyEncoding = 'UTF8' | 'BASE64';
export type HashAlgorithm = 'SHA1' | 'SHA256';
export type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

/**
 * Plugin API v6, generated from the plugin-api module. Do not edit.
 */
export interface MilkbeatPluginApi {
  manifest: PluginManifest;
  operations: {
    'lifecycle.warmUp': {};
    'lifecycle.settingsChanged': {};
    'metadata.home': {
      request: HomeRequest;
      response: MetadataPage;
    };
    'metadata.search': {
      request: SearchRequest;
      response: MetadataPage;
    };
    'metadata.suggest': {
      request: SuggestRequest;
      response: Suggestions;
    };
    'metadata.entity': {
      request: PageRequest;
      response: MetadataPage;
    };
    'metadata.tracks': {
      request: TracksRequest;
      response: TrackList;
    };
    'metadata.library': {
      request: LibraryRequest;
      response: MetadataPage;
    };
    'metadata.personalCollections': {
      request: PersonalCollectionsRequest;
      response: PersonalCollectionsPage;
    };
    'metadata.importPrivatePlaylist': {
      request: PrivatePlaylistImportRequest;
      response: PrivatePlaylistImportResult;
    };
    'metadata.radio': {
      request: RadioRequest;
      response: TrackList;
    };
    'audio.resolve': {
      request: ResolveAudioRequest;
      response: AudioStream;
    };
    'audio.match': {
      request: MatchAudioRequest;
      response: AudioMatches;
    };
    'audio.matchBatch': {
      request: MatchAudioBatchRequest;
      response: AudioMatchesBatch;
    };
    'audio.radio': {
      request: RadioRequest;
      response: TrackList;
    };
    'audio.reportPlayback': {
      request: ReportPlaybackRequest;
    };
    'video.search': {
      request: SearchRequest;
      response: MetadataPage;
    };
    'video.suggest': {
      request: SuggestRequest;
      response: Suggestions;
    };
    'video.entity': {
      request: PageRequest;
      response: MetadataPage;
    };
    'video.tracks': {
      request: TracksRequest;
      response: TrackList;
    };
    'video.related': {
      request: PageRequest;
      response: MetadataPage;
    };
    'video.resolve': {
      request: ResolveVideoRequest;
      response: VideoPlayback;
    };
    'video.comments': {
      request: CommentsRequest;
      response: CommentsPage;
    };
    'video.liveChat': {
      request: LiveChatRequest;
      response: LiveChatBatch;
    };
    'video.reportPlayback': {
      request: ReportPlaybackRequest;
    };
    'signIn.begin': {
      request: DeviceCodeBeginRequest;
      response: DeviceCodeChallenge;
    };
    'signIn.poll': {
      request: DeviceCodeSession;
      response: DeviceCodePollResult;
    };
    'signIn.confirm': {
      request: DeviceCodeSession;
      response: ProviderAccount;
    };
    'signIn.cancel': {
      request: DeviceCodeSession;
    };
    'signIn.complete': {
      request: WebLoginResult;
      response: ProviderAccount;
    };
    'signIn.account': {
      response: ProviderAccount;
    };
    'signIn.signOut': {};
    'settings.options': {
      request: SettingOptionsRequest;
      response: SettingOption[];
    };
  };
  host: {
    'http.fetch': {
      request: HttpRequest;
      response: HttpResponse;
    };
    'storage.get': {
      request: StorageKey;
      response: StoredValue;
    };
    'storage.set': {
      request: StorageEntry;
    };
    'storage.delete': {
      request: StorageKey;
    };
    'secrets.get': {
      request: StorageKey;
      response: StoredValue;
    };
    'secrets.set': {
      request: StorageEntry;
    };
    'secrets.delete': {
      request: StorageKey;
    };
    'crypto.hash': {
      request: HashRequest;
      response: HashResult;
    };
    'crypto.randomBytes': {
      request: RandomBytesRequest;
      response: RandomBytesResult;
    };
    'crypto.hmac': {
      request: HmacRequest;
      response: HashResult;
    };
    'code.load': {
      request: CodeLoadRequest;
      response: CodeLoadResult;
    };
    'assets.read': {
      request: AssetRequest;
      response: AssetText;
    };
    'env.get': {
      response: HostEnvironment;
    };
    'log.write': {
      request: LogRequest;
    };
    'browser.open': {
      request: BrowserOpenRequest;
      response: BrowserSession;
    };
    'browser.evaluate': {
      request: BrowserEvaluateRequest;
      response: BrowserResult;
    };
    'browser.close': {
      request: BrowserSession;
    };
    'settings.get': {
      response: StorageEntry[];
    };
    'time.sleep': {
      request: SleepRequest;
    };
    'signIn.refresh': {
      request: WebLoginRefreshRequest;
      response: WebLoginResult;
    };
  };
  error: PluginError;
}
export interface PluginManifest {
  format: number;
  api: ApiRange;
  id: string;
  name: string;
  version: string;
  versionCode: number;
  description?: string | null;
  author?: null | Author;
  updateUrl?: string | null;
  entry?: string;
  icon?: string | null;
  roles: Roles;
  signIn?: SignInMethod[];
  permissions?: Permissions;
  settings?: SettingDefinition[];
}
export interface ApiRange {
  min: number;
  target: number;
}
export interface Author {
  name: string;
  url?: string | null;
}
export interface Roles {
  metadata?: null | MetadataRole;
  audio?: null | AudioRole;
  video?: null | VideoRole;
}
export interface MetadataRole {
  surfaces: MetadataSurface[];
  entities: EntityKind[];
  idSpace: string;
  personalCollections?: boolean;
  privatePlaylistImport?: boolean;
}
export interface AudioRole {
  idSpaces: string[];
  match?: boolean;
  radio?: boolean;
  musicVideo?: boolean;
  reportPlayback?: boolean;
  delivery?: AudioDelivery;
  batchMatching?: boolean;
}
export interface VideoRole {
  idSpace: string;
  surfaces: VideoSurface[];
  live?: boolean;
  reportPlayback?: boolean;
}
export interface SignInMethodDeviceCode {
  type: 'deviceCode';
  id: string;
  label: string;
}
export interface SignInMethodWebLogin {
  type: 'webLogin';
  id: string;
  label: string;
  startUrl: string;
  successUrlPrefix: string;
  cookieUrl: string;
  requiredCookies: string[];
  extractScript?: string | null;
  refreshUrl?: string | null;
  pageScript?: string | null;
}
export interface Permissions {
  network?: string[];
  browser?: string[];
  storage?: number;
}
export interface SettingDefinition {
  key: string;
  type: SettingType;
  label: string;
  description?: string | null;
  default?: string | null;
  options?: SettingOption[];
  dynamicOptions?: boolean;
}
export interface SettingOption {
  value: string;
  label: string;
}
export interface HomeRequest {
  filterId?: string | null;
  cursor?: string | null;
}
export interface MetadataPage {
  id: string;
  blocks: PageBlock[];
  filters?: null | FilterControl;
  nextCursor?: string | null;
}
export interface PageBlockCollection {
  type: 'collection';
  id: string;
  header: null | CollectionHeader;
  layout: CollectionLayout;
  defaultItemView: ItemView;
  items: MetadataItem[];
  showAll?: null | EntityRef;
  showAllFilterId?: string | null;
}
export interface CollectionHeader {
  title: string;
  context?: string | null;
  avatar?: null | Artwork;
  target?: null | EntityRef;
}
export interface Artwork {
  url: string;
  width?: number | null;
  height?: number | null;
}
export interface EntityRef {
  kind: EntityKind;
  providerId: string;
}
export interface MetadataItem {
  id: string;
  entity: EntityRef;
  title: string;
  subtitle?: string | null;
  artwork?: null | Artwork;
  view?: null | ItemView;
  artists?: ArtistCredit[];
  durationSeconds?: number | null;
  explicit?: boolean;
  ordinal?: number | null;
  album?: string | null;
  details?: string[];
  live?: boolean;
  upcoming?: boolean;
  track?: null | TrackDescriptor;
}
export interface ArtistCredit {
  name: string;
  entity?: null | EntityRef;
}
export interface TrackDescriptor {
  ref: EntityRef;
  title: string;
  artists?: ArtistCredit[];
  album?: string | null;
  albumRef?: null | EntityRef;
  durationMs?: number | null;
  explicit?: boolean;
  artwork?: null | Artwork;
  trackNumber?: number | null;
  discNumber?: number | null;
  year?: number | null;
  hasVideo?: boolean;
  ids?: {
    [k: string]: string;
  };
}
export interface PageBlockHeader {
  type: 'header';
  id: string;
  style: HeaderStyle;
  entity: EntityRef;
  title: string;
  artwork?: null | Artwork;
  details?: string[];
  attribution?: null | Attribution;
  description?: string | null;
  tracks?: null | EntityRef;
  station?: null | EntityRef;
}
export interface Attribution {
  name: string;
  avatar?: null | Artwork;
  entity?: null | EntityRef;
}
export interface FilterControl {
  options: FilterOption[];
}
export interface FilterOption {
  id: string;
  label: string;
}
export interface SearchRequest {
  query: string;
  filterId?: string | null;
  cursor?: string | null;
}
export interface SuggestRequest {
  query: string;
}
export interface Suggestions {
  queries: string[];
}
export interface PageRequest {
  entity: EntityRef;
  filterId?: string | null;
  cursor?: string | null;
}
export interface TracksRequest {
  entity: EntityRef;
  cursor?: string | null;
}
export interface TrackList {
  tracks: TrackDescriptor[];
  next?: string | null;
  source?: null | EntityRef;
  filters?: null | FilterControl;
  selectedFilterId?: string | null;
  revision?: string | null;
}
export interface LibraryRequest {
  section?: string | null;
  cursor?: string | null;
}
export interface PersonalCollectionsRequest {
  cursor?: string | null;
  expectedAccountKey?: string | null;
}
export interface PersonalCollectionsPage {
  collections: PersonalCollection[];
  next?: string | null;
}
export interface PersonalCollection {
  ref: EntityRef;
  title: string;
  kind: PersonalCollectionKind;
  revision?: string | null;
  trackCount?: number | null;
  artwork?: null | Artwork;
}
export interface PrivatePlaylistImportRequest {
  sourceKey: string;
  title: string;
  tracks: EntityRef[];
  target?: null | EntityRef;
  expectedAccountKey?: string | null;
  cursor?: string | null;
  mode?: PrivatePlaylistImportMode;
  startIndex?: number | null;
  artwork?: null | PlaylistArtwork;
}
export interface PlaylistArtwork {
  dataBase64: string;
  mimeType: string;
  sizeBytes: number;
}
export interface PrivatePlaylistImportResult {
  ref?: null | EntityRef;
  next?: string | null;
  retryAfterMs?: number | null;
  progress?: null | PrivatePlaylistImportProgress;
}
export interface PrivatePlaylistImportProgress {
  phase: PrivatePlaylistImportPhase;
  completed: number;
  total: number;
}
export interface RadioRequest {
  seed: EntityRef;
  cursor?: string | null;
  filterId?: string | null;
}
export interface ResolveAudioRequest {
  track: TrackDescriptor;
  quality?: AudioQuality;
  video?: boolean;
  maxVideoHeight?: number | null;
  videoCodecs?: string[];
  language?: string | null;
  failure?: null | StreamFailure;
}
export interface StreamFailure {
  url: string;
  status?: number | null;
  reloadPlaybackContext?: string | null;
  serverAbrFailure?: null | ServerAbrFailure;
}
export interface AudioStream {
  url: string;
  cacheKey: string;
  renditionId: string;
  mimeType: string;
  codecs?: string | null;
  bitrate?: number | null;
  contentLength?: number | null;
  headers?: {
    [k: string]: string;
  };
  expiresInMs?: number | null;
  loudnessDb?: number | null;
  trackingToken?: string | null;
  video?: null | MediaFormat;
  drm?: null | AudioDrm;
  serverAbr?: null | ServerAbrPlayback;
  artwork?: null | Artwork;
  requireAudioOnlyHls?: boolean;
}
export interface MediaFormat {
  id: string;
  type: FormatType;
  url: string;
  mimeType: string;
  codecs?: string | null;
  width?: number | null;
  height?: number | null;
  fps?: number | null;
  bitrate?: number | null;
  averageBitrate?: number | null;
  contentLength?: number | null;
  durationMs?: number | null;
  initRange?: null | ByteRange;
  indexRange?: null | ByteRange;
  qualityLabel?: string | null;
  hdr?: boolean;
  audioTrack?: null | AudioTrackInfo;
  headers?: {
    [k: string]: string;
  };
}
export interface ByteRange {
  start: number;
  end: number;
}
export interface AudioTrackInfo {
  id: string;
  name?: string | null;
  language?: string | null;
  original?: boolean;
  drc?: boolean;
}
export interface AudioDrm {
  scheme: AudioDrmScheme;
  licenseUrl: string;
  headers?: {
    [k: string]: string;
  };
}
export interface ServerAbrPlayback {
  url: string;
  videoId: string;
  config: string;
  client: ServerAbrClientInfo;
  formats: ServerAbrFormat[];
  poToken?: string | null;
  visitorCookie?: string | null;
  durationMs?: number | null;
  live?: boolean;
}
export interface ServerAbrClientInfo {
  clientName: number;
  clientVersion: string;
  deviceMake?: string | null;
  deviceModel?: string | null;
  osName?: string | null;
  osVersion?: string | null;
  hl?: string | null;
  gl?: string | null;
  utcOffsetMinutes?: number | null;
}
export interface ServerAbrFormat {
  format: MediaFormat;
  itag: number;
  lastModified: string;
  xTags?: string | null;
}
export interface MatchAudioRequest {
  track: TrackDescriptor;
  strategy?: AudioMatchStrategy;
}
export interface AudioMatches {
  candidates?: TrackDescriptor[];
  error?: null | PluginError;
}
export interface PluginError {
  code: PluginErrorCode;
  message: string;
  userMessage?: string | null;
  retryAfterMs?: number | null;
  detail?: string | null;
}
export interface MatchAudioBatchRequest {
  tracks: TrackDescriptor[];
  strategy?: AudioMatchStrategy;
  playlist?: null | PrivatePlaylistImportRequest;
}
export interface AudioMatchesBatch {
  matches: AudioMatches[];
  playlist?: null | PrivatePlaylistImportResult;
  playlistError?: null | PluginError;
}
export interface ReportPlaybackRequest {
  entity: EntityRef;
  trackingToken?: string | null;
  playedMs: number;
  durationMs?: number | null;
}
export interface ResolveVideoRequest {
  entity: EntityRef;
  maxHeight?: number | null;
  codecs?: string[];
  language?: string | null;
  captionLanguage?: string | null;
  failure?: null | StreamFailure;
}
export interface VideoPlayback {
  kind: VideoKind;
  details: VideoDetails;
  formats?: MediaFormat[];
  hlsUrl?: string | null;
  dashUrl?: string | null;
  captions?: CaptionTrack[];
  chapters?: Chapter[];
  skipSegments?: SkipSegment[];
  headers?: {
    [k: string]: string;
  };
  expiresInMs?: number | null;
  startsInMs?: number | null;
  availableInMs?: number | null;
  dvr?: boolean;
  trackingToken?: string | null;
  serverAbr?: null | ServerAbrPlayback;
}
export interface VideoDetails {
  entity: EntityRef;
  title: string;
  channelName?: string | null;
  channel?: null | EntityRef;
  channelAvatar?: null | Artwork;
  durationSeconds?: number | null;
  description?: string | null;
  viewsLabel?: string | null;
  publishedLabel?: string | null;
  artwork?: null | Artwork;
  keywords?: string[];
}
export interface CaptionTrack {
  url: string;
  language: string;
  name: string;
  autoGenerated?: boolean;
  translated?: boolean;
  mimeType?: string;
}
export interface Chapter {
  title: string;
  startMs: number;
}
export interface SkipSegment {
  startMs: number;
  endMs: number;
  category: string;
}
export interface CommentsRequest {
  entity: EntityRef;
  sortId?: string | null;
  cursor?: string | null;
}
export interface CommentsPage {
  comments: Comment[];
  next?: string | null;
  sorts?: FilterOption[];
  totalLabel?: string | null;
}
export interface Comment {
  id: string;
  author: string;
  authorAvatar?: null | Artwork;
  text: string;
  publishedLabel?: string | null;
  likesLabel?: string | null;
  replyCount?: number;
  repliesCursor?: string | null;
  pinned?: boolean;
  byCreator?: boolean;
}
export interface LiveChatRequest {
  entity: EntityRef;
  cursor?: string | null;
}
export interface LiveChatBatch {
  messages: LiveChatMessage[];
  next?: string | null;
  pollAfterMs?: number;
}
export interface LiveChatMessage {
  id: string;
  author: string;
  authorAvatar?: null | Artwork;
  text: string;
  highlight?: string | null;
}
export interface DeviceCodeBeginRequest {
  method: string;
}
export interface DeviceCodeChallenge {
  session: string;
  userCode: string;
  verificationUri: string;
  verificationUriComplete?: string | null;
  intervalMs: number;
  expiresInMs?: number | null;
  message?: string | null;
}
export interface DeviceCodeSession {
  session: string;
}
export interface DeviceCodePollResult {
  status: DeviceCodeStatus;
  account?: null | ProviderAccount;
  intervalMs?: number | null;
}
export interface ProviderAccountAnonymous {
  type: 'anonymous';
}
export interface ProviderAccountExpired {
  type: 'expired';
}
export interface ProviderAccountSignedIn {
  type: 'signedIn';
  key: string;
  name?: string | null;
  avatar?: null | Artwork;
}
export interface WebLoginResult {
  method: string;
  cookies: string;
  extracted?: {
    [k: string]: string;
  };
}
export interface SettingOptionsRequest {
  key: string;
}
export interface HttpRequest {
  url: string;
  method?: string;
  headers?: {
    [k: string]: string;
  };
  body?: string | null;
  timeoutMs?: number | null;
  followRedirects?: boolean;
  bodyEncoding?: HttpBodyEncoding;
  responseEncoding?: HttpBodyEncoding;
}
export interface HttpResponse {
  status: number;
  url: string;
  headers: {
    [k: string]: string;
  };
  body: string;
}
export interface StorageKey {
  key: string;
}
export interface StoredValue {
  value?: string | null;
}
export interface StorageEntry {
  key: string;
  value: string;
}
export interface HashRequest {
  algorithm: HashAlgorithm;
  text: string;
}
export interface HashResult {
  hex: string;
}
export interface RandomBytesRequest {
  length: number;
}
export interface RandomBytesResult {
  hex: string;
}
export interface HmacRequest {
  algorithm: HashAlgorithm;
  keyHex: string;
  messageHex: string;
}
export interface CodeLoadRequest {
  key: string;
  source?: string | null;
}
export interface CodeLoadResult {
  loaded: boolean;
}
export interface AssetRequest {
  path: string;
}
export interface AssetText {
  text: string;
}
export interface HostEnvironment {
  apiVersion: number;
  appVersion: string;
  locale: string;
  region: string;
  deviceClass: string;
  pluginVersion: string;
  osVersion?: string | null;
  deviceModel?: string | null;
}
export interface LogRequest {
  level: LogLevel;
  message: string;
}
export interface BrowserOpenRequest {
  html: string;
  baseUrl: string;
  timeoutMs?: number | null;
  userAgent?: string | null;
}
export interface BrowserSession {
  id: string;
}
export interface BrowserEvaluateRequest {
  session: string;
  script: string;
  timeoutMs?: number | null;
}
export interface BrowserResult {
  value: string;
}
export interface SleepRequest {
  ms: number;
}
export interface WebLoginRefreshRequest {
  method: string;
  cookies: string;
}

export const HOST_OPERATIONS = [
  'http.fetch',
  'storage.get',
  'storage.set',
  'storage.delete',
  'secrets.get',
  'secrets.set',
  'secrets.delete',
  'crypto.hash',
  'crypto.randomBytes',
  'crypto.hmac',
  'code.load',
  'assets.read',
  'env.get',
  'log.write',
  'browser.open',
  'browser.evaluate',
  'browser.close',
  'settings.get',
  'time.sleep',
  'signIn.refresh'
] as const;
