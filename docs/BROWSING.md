# Organize browsing

Select a top tab, such as Live TV or Sports, then open Tools → Sort this section. Each tab keeps its own sorting choice. US groups come first by default in Live TV and Sports; this can be switched off in the same menu. Pinned channels still take priority.

Tools → Hide groups from browsing lets you hide countries or individual provider groups from the current tab. AU and CA are initially hidden in Live TV and Sports. Search still includes groups hidden this way, across the full library. Show all groups restores browsing visibility for the current tab.

Long-press OK on a channel to rename it, change its category, pin it, move it, or hide it individually. Individually hidden channels remain excluded from ordinary search; use the Hidden tab to recover them. Top-level tabs are fixed; these controls organize their contents.

Implementation: category lists are built from displayed categories without sorting the channel catalog. Category preparation and channel/guide/history row preparation run off the UI thread. Filters are applied before sorting. New browsing settings are scoped to both source and section.

## Live sports and PPV

Sports opens on **Live now**. This uses current guide start/end times plus a live flag or an explicit LIVE programme title. It excludes 24/7 groups, replays, news, highlights, and off-air placeholders. Live PPVs are included and labeled **LIVE • PPV**. It reevaluates the current guide every 30 seconds while this view is visible.

**PPV / event channels** lists event channels regardless of schedule. **Provider live (unverified)** lists channels whose names say LIVE; those labels may be stale and are not proof of a current live event. **All** restores full Sports browsing. Search continues to search the entire library.

Xtream sources use their server's XMLTV guide automatically if no explicit EPG URL is saved. The guide must identify current live events for the strict Live now list to populate. Missing guide data does not mean nothing is happening. A guide marked live is a provider assertion, not video-content verification.
