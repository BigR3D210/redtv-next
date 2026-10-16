package com.redtv.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.redtv.app.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrefsTest {
    private lateinit var prefs: Prefs
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("redtv", 0).edit().clear().commit()
        val values=mutableMapOf<String,String>()
        prefs = Prefs(context,object:TextStore {
            override fun read(name:String)=values[name]
            override fun write(name:String,value:String){values[name]=value}
            override fun remove(name:String){values.remove(name)}
        })
    }
    @Test fun favoriteOfOneProviderDoesNotLeakIntoAnother() {
        prefs.activeProfileId = "a"
        prefs.toggleFavorite("live_12")
        prefs.activeProfileId = "b"
        assertFalse(prefs.isFavorite("live_12"))
        prefs.activeProfileId = "a"
        assertTrue(prefs.isFavorite("live_12"))
    }
    @Test fun searchFindsOtherSectionsAndCustomNamesAndRejectsUnrelatedChannels() {
        val sports=Channel("s","ESPN","https://example.test/s",section=Section.SPORTS)
        val news=Channel("n","News","https://example.test/n",section=Section.LIVE)
        val items=listOf(sports,news)
        assertEquals(listOf(sports),prefs.searchChannels(items," espn "))
        assertTrue(prefs.searchChannels(items,"zzzxxx").isEmpty())
        prefs.setChannelName(news.id,"Local ESPN news")
        assertEquals(items,prefs.searchChannels(items,"ESPN"))
    }
    @Test fun resumeIsIndependentBetweenProviders() {
        prefs.activeProfileId = "a"
        prefs.setResumePosition("movie_12", 45000)
        prefs.activeProfileId = "b"
        assertEquals(0L, prefs.resumePosition("movie_12"))
    }
    @Test fun episodeHistorySurvivesCatalogRemovalAndCompletionClearsResume() {
        prefs.activeProfileId="a"
        val episode=Channel("a:ep_1","Episode one","https://example.test/1",kind=ContentKind.EPISODE)
        prefs.recordPlayback(episode,45000,1000000)
        assertEquals(listOf(episode),prefs.historyChannels())
        assertEquals(listOf(episode.id),prefs.continueIds())
        prefs.recordPlayback(episode,1000000,1000000,true)
        assertEquals(0L,prefs.resumePosition(episode.id))
        assertTrue(prefs.continueIds().isEmpty())
        assertTrue(prefs.watchHistory().single().completed)
    }
    @Test fun hiddenChannelCanBeRecoveredAndReordered() {
        val a=Channel("a","A","https://example.test/a")
        val b=Channel("b","B","https://example.test/b")
        prefs.setHidden(a.id,true);assertTrue(prefs.isHidden(a.id))
        prefs.setHidden(a.id,false);assertFalse(prefs.isHidden(a.id))
        prefs.moveChannel(b.id,-1,listOf(a.id,b.id))
        assertEquals(listOf(b.id,a.id),prefs.orderedChannels(listOf(a,b)).map{it.id})
        prefs.setChannelName(a.id,"My news")
        assertEquals("My news",prefs.displayChannel(a).name)
    }
}
