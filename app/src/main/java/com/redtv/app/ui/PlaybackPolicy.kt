package com.redtv.app.ui

/** State scoped to one selected item; READY never replenishes the retry budget. */
class PlaybackPolicy {
    var canRecord = false
        private set
    private var retries = 0
    fun begin() { canRecord = false; retries = 0 }
    fun ready() { canRecord = true }
    fun stable() { retries = 0 }
    fun retryDelay(): Long? = if (retries < 3) (1_000L shl retries++) else null

    companion object {
        fun sequential(enabled: Boolean, kind: String, nextKind: String,
                       series: String?, nextSeries: String?, season: Int?, episode: Int?,
                       nextSeason: Int?, nextEpisode: Int?): Boolean =
            enabled && kind == "episode" && nextKind == "episode" &&
                !series.isNullOrBlank() && series == nextSeries && episode != null &&
                season != null && ((season == nextSeason && nextEpisode == episode + 1) ||
                (nextSeason == season + 1 && nextEpisode == 1))
    }
}
