# Red TV personal player upgrade

User approved the review fixes and a complete free personal player upgrade. Keep Kotlin/Media3 and native TV navigation. No accounts, telemetry, fees or hosted service dependencies. Work in this project; initialize its own repository because the enclosing repository is the user's home directory. Preserve a baseline commit. Do not publish or push.

## Tasks and ownership
1. Root: build baseline; add behavior tests for playlist identity and parsing; introduce explicit content kinds, provider-scoped identity, disk catalog and episode persistence, per-provider config cache, durable watch history, resume completion, ordering/rename/category overrides and encrypted credential storage. Preserve old preferences through scoped migration where unambiguous.
2. Player implementer: use the shared contracts below; recover state after process death, bounded retries, optional episode autoplay, history/resume, previous channel, sleep timer, subtitle/audio defaults, local file/link support and 2/4 pane multiview. Own PlayerActivity, PlaybackEngine, MultiViewActivity and their layouts/tests.
3. LAN implementer: secure session-limited pairing, laptop channel management with search and bulk operations, direct URL handoff, source editing without password disclosure, paired TV sync with explicit limitations. Own ConfigServer, EditOnLaptopActivity, net/RoomSync*, assets/remote*, companion laptop server and tests. No external servers or automatic network scanning.
4. Root: browser UI improvements, guide timeline, details/seasons, watch history, sorting/customization/hidden management, direct play, multiview picker, paired-room launch, source refresh races and lifecycle restoration.
5. Root: release signing, reproducible wrapper, diagnostics and test runner; unit tests + build + lint; review integration and package APK/source/docs. Real TV decoder, remote and room sync tests remain explicit if hardware unavailable.

## Shared contracts
Channel retains existing constructor parameters and adds defaults: kind:String (ContentKind.LIVE/MOVIE/SERIES/EPISODE), providerId:String, sourceId:String, seriesId:String?, season:Int?, episode:Int?, description:String?, durationSeconds:Long?, headers:Map<String,String>. isVod:Boolean and isSeries:Boolean getters. IDs include provider prefix. sourceId is original provider ID for series API. Channel fields are immutable.
Prefs retains existing API. New: watchHistory():List<WatchEntry>; recordPlayback(channel:Channel, positionMs:Long, durationMs:Long, completed:Boolean=false); historyChannels():List<Channel>; removeHistory(id), clearHistory(); autoPlay:Boolean (default false); resizeMode:Int (0); subtitleEnabled:Boolean (false); audioLanguage:String; subtitleLanguage:String; sortMode:String; displayChannel(ch):Channel; setChannelName(id,name); setChannelCategory(id,category); moveChannel(id,delta,visibleIds:List<String>); setChannelOrder(ids); orderedChannels(list):List<Channel>; setFavorite(id,Boolean); setHidden(id,Boolean); cachedConfig remains active-profile scoped. Mutations thread-safe where read-modify-write.
ContentRepository: initialize(context); restore(prefs):Boolean loads active cached catalog and remembered episodes; reload(prefs) fetches catalog only; refreshEpg(prefs) separate; allChannels():List<Channel>; channelById; loadEpisodes(seriesId:String) accepts full stable series ID or provider source ID; launchQueue:List<Channel> process-local for normal use, persisted via createQueue(context,items):String and readQueue(context,key):List<Channel>; remember(channels). Item launch uses PlayerActivity.EXTRA_QUEUE (queue key) and EXTRA_INDEX; legacy EXTRA_IDS supported. No credentials in logs/UI errors.
Room sync implementation defines public contract in docs/ROOM-SYNC-CONTRACT.md before player integration. Pairing deliberately started by user, expiring tokens and rate limits. Sync shares stream credentials only with paired local devices; join UI explicitly explains this. HLS absolute program time when available; VOD media position otherwise. Untimed live streams best effort, never claim exact sync. No relay/transcoding promised.

## Acceptance and tests
Behavior tests: same EPG different URLs distinct IDs; playlist reorder stable IDs; quoted commas retained; metadata/header support; profile isolation; completion threshold; sync drift and auth lockout; restore queue; source edit triggers reload; hidden recovery; history episodes persist; replay does not become watched before READY.
Run JUnit tests on JVM; compile APK and run Android lint. LAN HTTP/session and laptop range-request tests. If emulator available test D-pad flow using sample local content. Deliver honest device test checklist and compatibility limitations.

## Rulings
- Existing user approval covers implementation; do not repeat design/spec approval.
- Multi-room uses explicit LAN pairing, without cloud login; free to run, source limits remain.
- Separate content variants by URL-derived stable identity, and keep EPG IDs independent.
- No destructive cleanup or automatic publication.
