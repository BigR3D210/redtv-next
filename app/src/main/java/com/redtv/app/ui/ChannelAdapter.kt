package com.redtv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import coil.load
import com.redtv.app.R
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Prefs
import com.redtv.app.model.Channel

data class ChannelRow(val channel:Channel,val favorite:Boolean,val subtitle:String)

class ChannelAdapter(private val prefs:Prefs,private val onClick:(Channel,Int)->Unit,private val onLongPress:(Channel)->Unit,
                     private val onFocus:(Channel?)->Unit = {}):
    ListAdapter<ChannelRow,ChannelAdapter.VH>(object:DiffUtil.ItemCallback<ChannelRow>() {
        override fun areItemsTheSame(a:ChannelRow,b:ChannelRow)=a.channel.id==b.channel.id
        override fun areContentsTheSame(a:ChannelRow,b:ChannelRow)=a==b
    }) {
    fun submit(items:List<Channel>,done:Runnable?=null) {
        val favorites=prefs.favorites()
        val history=prefs.watchHistory().associateBy {it.channel.id}
        submitList(items.map {ch->
            val entry=history[ch.id]
            val now=ContentRepository.nowAndNext(ch.epgChannelId).first
            val subtitle=when {
                entry?.completed==true && ch.isVod -> "✓ Watched"
                ch.isVod && entry!=null && entry.positionMs>10000 -> "Resume • ${entry.positionMs/60000} min"
                now!=null -> now.title
                ch.isSeries -> "Browse seasons"
                ch.season!=null -> "Season ${ch.season} • Episode ${ch.episode ?: ""}"
                ch.number!=null -> "Channel ${ch.number}"
                else -> ch.category
            }
            ChannelRow(ch,ch.id in favorites,subtitle)
        },done)
    }
    fun currentIds()=ArrayList(currentList.map {it.channel.id})
    fun indexOfId(id:String)=currentList.indexOfFirst {it.channel.id==id}
    override fun getItemViewType(position:Int)=if(getItem(position).channel.isSeries || getItem(position).channel.kind=="movie")1 else 0
    class VH(view:View):androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val logo:ImageView=view.findViewById(R.id.logo)
        val star:ImageView=view.findViewById(R.id.favStar)
        val name:TextView=view.findViewById(R.id.name)
        val sub:TextView=view.findViewById(R.id.epgNow)
    }
    override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):VH {
        val view=LayoutInflater.from(parent.context).inflate(R.layout.item_channel,parent,false)
        val logo=view.findViewById<ImageView>(R.id.logo)
        val frame=logo.parent as View
        frame.layoutParams=frame.layoutParams.apply {height=((if(viewType==1)172 else 90)*parent.resources.displayMetrics.density).toInt()}
        logo.scaleType=if(viewType==1)ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        return VH(view)
    }
    override fun onBindViewHolder(holder:VH,position:Int) {
        val row=getItem(position);val ch=row.channel
        holder.name.text=ch.name;holder.sub.text=row.subtitle
        holder.sub.visibility=View.VISIBLE
        holder.star.visibility=if(row.favorite)View.VISIBLE else View.GONE
        holder.logo.load(ch.logoUrl){placeholder(R.drawable.logo_placeholder);error(R.drawable.logo_placeholder)}
        holder.itemView.contentDescription=ch.name+", "+row.subtitle
        holder.itemView.setOnClickListener {if(holder.bindingAdapterPosition!=androidx.recyclerview.widget.RecyclerView.NO_POSITION)onClick(ch,holder.bindingAdapterPosition)}
        holder.itemView.setOnLongClickListener {onLongPress(ch);true}
        holder.itemView.setOnFocusChangeListener { _, focused -> onFocus(if(focused) ch else null) }
    }
}

