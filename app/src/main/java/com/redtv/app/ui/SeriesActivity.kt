package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivitySeriesBinding
import com.redtv.app.model.Channel
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SeriesActivity:AppCompatActivity() {
    private lateinit var b:ActivitySeriesBinding
    private lateinit var prefs:Prefs
    private lateinit var adapter:ChannelAdapter
    private var episodes=emptyList<Channel>()
    private var season:Int?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        b=ActivitySeriesBinding.inflate(layoutInflater);setContentView(b.root);prefs=Prefs(this)
        ContentRepository.initialize(this)
        b.seriesTitle.text=intent.getStringExtra(EXTRA_TITLE) ?: "Series"
        season=state?.getInt("season")?.takeIf {it>=0}
        adapter=ChannelAdapter(prefs,{ch,_->details(ch)},{ch->details(ch)})
        b.episodeGrid.layoutManager=GridLayoutManager(this,4);b.episodeGrid.adapter=adapter
        b.btnSeason.setOnClickListener {
            val seasons=episodes.mapNotNull {it.season}.distinct().sorted()
            AlertDialog.Builder(this).setTitle("Season").setItems((listOf("All seasons")+seasons.map {"Season $it"}).toTypedArray()){_,i->season=if(i==0)null else seasons[i-1];showEpisodes()}.show()
        }
        val id=intent.getStringExtra(EXTRA_SERIES_ID) ?: run{finish();return}
        b.progress.visibility=View.VISIBLE
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO){ContentRepository.restore(prefs)}
                if(ContentRepository.config==null)ContentRepository.reload(prefs)
                episodes=ContentRepository.loadEpisodes(id)
                showEpisodes()
            }catch(e:CancellationException){throw e}
            catch(_:Exception){b.emptyState.text="Couldn't load episodes. Return to the library and refresh your source.";b.emptyState.visibility=View.VISIBLE}
            finally{b.progress.visibility=View.GONE}
        }
    }
    override fun onResume(){super.onResume();if(::adapter.isInitialized && episodes.isNotEmpty())showEpisodes()}
    override fun onSaveInstanceState(out:Bundle){out.putInt("season",season ?: -1);super.onSaveInstanceState(out)}
    private fun showEpisodes() {
        b.btnSeason.text=season?.let{"Season $it"} ?: "All seasons"
        val visible=episodes.filter {season==null || it.season==season}
        adapter.submit(visible);b.emptyState.visibility=if(visible.isEmpty())View.VISIBLE else View.GONE
        b.emptyState.text="No episodes in this season."
    }
    private fun details(ch:Channel) {
        val pos=prefs.resumePosition(ch.id)
        AlertDialog.Builder(this).setTitle(ch.name).setMessage(ch.description ?: "Season ${ch.season ?: "?"} • Episode ${ch.episode ?: "?"}")
            .setPositiveButton(if(pos>10000)"Resume (${pos/60000} min)" else "Play"){_,_->play(ch)}
            .setNeutralButton(if(pos>10000)"Start over" else "Mark watched"){_,_->
                if(pos>10000){prefs.setResumePosition(ch.id,0);play(ch)}else {prefs.recordPlayback(ch,0,0,true);showEpisodes()}
            }.setNegativeButton("Back",null).show()
    }
    private fun play(ch:Channel){lifecycleScope.launch {
        val key=withContext(Dispatchers.IO){ContentRepository.createQueue(this@SeriesActivity,episodes)}
        startActivity(Intent(this@SeriesActivity,PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_QUEUE,key).putExtra(PlayerActivity.EXTRA_INDEX,episodes.indexOfFirst{it.id==ch.id}))
    }}
    companion object {const val EXTRA_SERIES_ID="series_id";const val EXTRA_TITLE="title"}
}


