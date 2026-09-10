package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.redtv.app.R
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.model.Channel
import com.redtv.app.model.ContentKind
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GuideActivity:AppCompatActivity() {
    private lateinit var prefs:Prefs
    private lateinit var rows:RecyclerView
    private lateinit var heading:TextView
    private lateinit var times:LinearLayout
    private var channels=emptyList<Channel>()
    private var start=0L
    private var favoritesOnly=false
    private val span=3*60*60*1000L
    private val format=SimpleDateFormat("EEE h:mm a",Locale.getDefault())
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);prefs=Prefs(this);ContentRepository.initialize(this)
        start=state?.getLong("start") ?: (System.currentTimeMillis()/1800000)*1800000
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(12),dp(24),dp(12));setBackgroundResource(R.color.bg)}
        heading=TextView(this).apply {textSize=22f;setTextColor(getColor(R.color.text_primary))};root.addView(heading)
        val controls=LinearLayout(this)
        fun button(label:String,action:()->Unit){controls.addView(Button(this).apply {text=label;setOnClickListener{action()}})}
        button("Back"){finish()};button("Earlier"){start-=span;render()};button("Now"){start=(System.currentTimeMillis()/1800000)*1800000;render()}
        button("Later"){start+=span;render()};button("Favorites / All"){favoritesOnly=!favoritesOnly;render()}
        button("Refresh"){lifecycleScope.launch {try{ContentRepository.refreshEpg(prefs);render()}catch(_:Exception){Toast.makeText(this@GuideActivity,"Guide refresh unavailable.",Toast.LENGTH_LONG).show()}}}
        root.addView(controls)
        times=LinearLayout(this);root.addView(times)
        rows=RecyclerView(this).apply {layoutManager=LinearLayoutManager(this@GuideActivity)}
        root.addView(rows,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        lifecycleScope.launch {
            withContext(Dispatchers.IO){if(!ContentRepository.hasAny())ContentRepository.restore(prefs)}
            channels=prefs.orderedChannels(ContentRepository.allChannels()).filter {it.kind==ContentKind.LIVE && !prefs.isHidden(it.id)}
            render()
        }
    }
    override fun onSaveInstanceState(out:Bundle){out.putLong("start",start);super.onSaveInstanceState(out)}
    private fun render() {
        heading.text="TV guide • ${format.format(Date(start))}–${SimpleDateFormat("h:mm a",Locale.getDefault()).format(Date(start+span))}"
        times.removeAllViews();times.addView(TextView(this).apply {text=if(favoritesOnly)"Favorites" else "Channels"},LinearLayout.LayoutParams(dp(175),dp(32)))
        repeat(6){i->times.addView(TextView(this).apply {text=SimpleDateFormat("h:mm a",Locale.getDefault()).format(Date(start+i*1800000));textSize=13f},LinearLayout.LayoutParams(0,dp(32),1f))}
        val items=channels.filter {!favoritesOnly || prefs.isFavorite(it.id)}
        rows.adapter=object:RecyclerView.Adapter<Row>() {
            override fun getItemCount()=items.size
            override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=Row(LinearLayout(parent.context).apply {orientation=LinearLayout.HORIZONTAL})
            override fun onBindViewHolder(holder:Row,position:Int) {
                val ch=items[position];holder.box.removeAllViews()
                holder.box.addView(Button(this@GuideActivity).apply {text=ch.name;textSize=14f;isAllCaps=false;setOnClickListener{watch(ch)}},LinearLayout.LayoutParams(dp(175),dp(76)))
                val track=LinearLayout(this@GuideActivity);holder.box.addView(track,LinearLayout.LayoutParams(0,dp(76),1f))
                val slots=GuideTimeline.slots(ContentRepository.epg[ch.epgChannelId].orEmpty(),start,start+span)
                slots.forEach {slot->
                    val p=slot.program
                    track.addView(Button(this@GuideActivity).apply {
                        text=p?.title ?: "No guide";textSize=13f;isAllCaps=false;maxLines=2
                        minWidth=0;minimumWidth=0;setPadding(dp(4),0,dp(4),0)
                        setBackgroundResource(R.drawable.bg_card)
                        if(p!=null && System.currentTimeMillis() in p.startMillis until p.stopMillis)setTextColor(getColor(R.color.accent))
                        setOnClickListener {
                            if(p==null)watch(ch) else AlertDialog.Builder(this@GuideActivity).setTitle(p.title)
                                .setMessage("${ch.name}\n${format.format(Date(p.startMillis))}\n\n${p.description.orEmpty()}")
                                .setPositiveButton("Watch channel live"){_,_->watch(ch)}.setNegativeButton("Back",null).show()
                        }
                    },LinearLayout.LayoutParams(0,dp(72),(slot.end-slot.start).toFloat()/span))
                }
            }
        }
        if(items.isEmpty())heading.text="TV guide • No ${if(favoritesOnly)"favorite " else ""}live channels available"
    }
    private fun watch(ch:Channel){lifecycleScope.launch {
        val key=withContext(Dispatchers.IO){ContentRepository.createQueue(this@GuideActivity,channels)}
        startActivity(Intent(this@GuideActivity,PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_QUEUE,key).putExtra(PlayerActivity.EXTRA_INDEX,channels.indexOfFirst{it.id==ch.id}))
    }}
    private class Row(val box:LinearLayout):RecyclerView.ViewHolder(box)
}

