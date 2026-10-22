# Organize browsing

Select a top tab, such as Live TV or Sports, then open Tools → Sort this section. Each tab keeps its own sorting choice. US groups come first by default in Live TV and Sports; this can be switched off in the same menu. Pinned channels still take priority.

Tools → Hide groups from browsing lets you hide countries or individual provider groups from the current tab. AU and CA are initially hidden in Live TV and Sports. Search still includes groups hidden this way, across the full library. Show all groups restores browsing visibility for the current tab.

Long-press OK on a channel to rename it, change its category, pin it, move it, or hide it individually. Individually hidden channels remain excluded from ordinary search; use the Hidden tab to recover them. Top-level tabs are fixed; these controls organize their contents.

Implementation: category lists are built from displayed categories without sorting the channel catalog. Category preparation and channel/guide/history row preparation run off the UI thread. Filters are applied before sorting. New browsing settings are scoped to both source and section.
