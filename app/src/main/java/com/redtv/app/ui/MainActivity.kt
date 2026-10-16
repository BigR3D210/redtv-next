package com.redtv.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.redtv.app.R
import com.redtv.app.data.ContentRepository
import com.redtv.app.data.Identity
import com.redtv.app.data.Prefs
import com.redtv.app.databinding.ActivityMainBinding
import com.redtv.app.model.*
import com.redtv.app.net.RoomSyncClient
import com.redtv.app.net.Updater
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: ChannelAdapter
    private val sections = listOf(Section.LIVE, Section.MOVIES, Section.SERIES, Section.SPORTS, "fav", "continue", "history", "hidden")
    private val labels = listOf("Live TV", "Movies", "Series", "Sports", "Favorites", "Continue", "History", "Hidden")
    private var section = Section.LIVE
    private var category = "All"
    private var displayed = emptyList<Channel>()
    private var catalog = emptyList<Channel>()
    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var loadGeneration = 0
    private var focusedId: String? = null
    private var resumeRefresh = false
    private var previewJob: Job? = null
    private var previewEngine: PlaybackEngine? = null
    private fun stopPreview() {
        previewJob?.cancel(); previewJob=null
        b.previewPlayer.player=null
        previewEngine?.release(); previewEngine=null
        b.previewBox.visibility=View.GONE
    }
    private fun preview(ch: Channel?) {
        stopPreview()
        if(!prefs.channelPreview || ch == null || ch.kind != ContentKind.LIVE) return
        previewJob=lifecycleScope.launch {
            delay(700)
            b.previewBox.visibility=View.VISIBLE
            val now=ContentRepository.nowAndNext(ch.epgChannelId).first?.title
            val title=listOfNotNull(ch.name,now).joinToString("\n")
            b.previewTitle.text=title
            val engine=PlaybackEngine(this@MainActivity,prefs,{ message ->
                b.previewTitle.text=if(message == null) title else "$title\n$message"
            },recordHistory=false)
            previewEngine=engine
            engine.player.volume=0f
            b.previewPlayer.player=engine.player
            engine.play(ch)
        }
    }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                if(it.moveToFirst()) it.getString(0) else null
            } }.getOrNull() ?: "Local video"
            playItems(listOf(Channel("direct_"+Identity.hash(uri.toString()),name,uri.toString(),kind=ContentKind.MOVIE)),0)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs=Prefs(this); ContentRepository.initialize(this)
        b=ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        section=state?.getString("section") ?: Section.LIVE
        category=state?.getString("category") ?: "All"
        focusedId=state?.getString("focus")
        adapter=ChannelAdapter(prefs,{ch,_->openItem(ch)},::contextMenu,::preview)
        b.channelGrid.layoutManager=GridLayoutManager(this,4)
        b.channelGrid.adapter=adapter
        b.categoryList.layoutManager=LinearLayoutManager(this)
        b.sectionList.layoutManager=LinearLayoutManager(this,LinearLayoutManager.HORIZONTAL,false)
        val tabs=CategoryAdapter(labels,R.layout.item_section) { index ->
            val selected=sections[index]
            if(section!=selected) { section=selected;category="All";focusedId=null; rebuildCategories() }
        }
        b.sectionList.adapter=tabs; tabs.setSelected(sections.indexOf(section).coerceAtLeast(0))
        b.btnSettings.text="Tools";b.btnSettings.setOnClickListener { toolsMenu() }
        b.btnSources.setOnClickListener { sources() }
        b.btnGuide.setOnClickListener { startActivity(Intent(this,GuideActivity::class.java)) }
        b.search.addTextChangedListener(object:TextWatcher {
            override fun afterTextChanged(s:Editable?) { applyFilter(true,s?.toString().orEmpty()) }
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {}
        })
        b.search.setOnEditorActionListener { _, action, event ->
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || event?.keyCode==android.view.KeyEvent.KEYCODE_ENTER) {
                applyFilter()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(b.search.windowToken,0)
                b.channelGrid.requestFocus()
                true
            } else false
        }
        b.search.setOnFocusChangeListener { _, focused ->
            if(focused) stopPreview() else applyFilter()
        }
        load()
    }
    override fun onResume() {
        super.onResume()
        if(!::b.isInitialized)return
        if(ContentRepository.dirty) {ContentRepository.dirty=false;load()}
        else if(resumeRefresh) { rebuildCategories();resumeRefresh=false }
    }
    override fun onPause() { stopPreview();resumeRefresh=true;super.onPause() }
    override fun onSaveInstanceState(out:Bundle) {
        out.putString("section",section);out.putString("category",category);out.putString("focus",focusedId)
        super.onSaveInstanceState(out)
    }
    private fun load() {
        loadJob?.cancel();filterJob?.cancel()
        val generation=++loadGeneration
        ContentRepository.dirty=false
        b.progress.visibility=View.VISIBLE
        loadJob=lifecycleScope.launch {
            try {
                val cached=withContext(Dispatchers.IO) {ContentRepository.restore(prefs)}
                if(generation!=loadGeneration)return@launch
                catalog=ContentRepository.allChannels()
                rebuildCategories()
                b.title.text="Red TV Next"
                if(!prefs.hasSource()) {showEmpty("Your player, your library. Add a source, or use Tools to open a link or local video.");return@launch}
                if(cached)b.progress.visibility=View.GONE
                ContentRepository.reload(prefs)
                if(generation!=loadGeneration)return@launch
                catalog=ContentRepository.allChannels();rebuildCategories();b.progress.visibility=View.GONE
                try { ContentRepository.refreshEpg(prefs);if(generation==loadGeneration)applyFilter() }
                catch(e:CancellationException){throw e}
                catch(_:Exception){toast("Channels loaded. Guide is unavailable; refresh later.")}
            } catch(e:CancellationException){throw e}
            catch(_:Exception) {
                if(catalog.isEmpty())showEmpty("Couldn't refresh this source. Check the connection in Sources or try Refresh.")
                else toast("Couldn't refresh. Showing your saved library.")
            } finally {if(generation==loadGeneration)b.progress.visibility=View.GONE}
        }
    }
    private fun rebuildCategories() {
        val items=prefs.orderedChannels(catalog.filter {it.section==section})
        val categories=if(section in sections.take(4))listOf("All")+items.map {it.category}.distinct().sorted() else listOf("All")
        if(category !in categories)category="All"
        val categoriesAdapter=CategoryAdapter(categories) {idx->
            if(category!=categories[idx]){category=categories[idx];focusedId=null;applyFilter()}
        }
        categoriesAdapter.setSelected(categories.indexOf(category))
        b.categoryList.adapter=categoriesAdapter
        (b.channelGrid.layoutManager as GridLayoutManager).spanCount=if(section==Section.MOVIES || section==Section.SERIES)5 else 4
        applyFilter()
    }
    private fun applyFilter(debounce:Boolean=false,searchText:String=b.search.text.toString()) {
        filterJob?.cancel()
        val selectedSection=section;val selectedCategory=category;val query=searchText.trim()
        filterJob=lifecycleScope.launch {
            if(debounce)delay(180)
            val result=withContext(Dispatchers.Default) {
                val history=if(query.isBlank()) prefs.historyChannels() else emptyList()
                val hidden=prefs.hidden()
                var items=if(query.isNotBlank()) prefs.searchChannels(catalog,query) else when(selectedSection) {
                    "fav" -> {val favorites=prefs.favorites();(catalog+history).distinctBy {it.id}.filter {it.id in favorites}}
                    "hidden" -> (catalog+history).distinctBy {it.id}.filter {it.id in hidden}
                    "history" -> history
                    "continue" -> {val ids=prefs.continueIds().toSet();history.filter {it.id in ids}}
                    else -> catalog.filter {it.section==selectedSection}
                }
                if(selectedSection!="hidden")items=items.filterNot {it.id in hidden}
                items=if(selectedSection=="history" || selectedSection=="continue") items.map {prefs.displayChannel(it)} else prefs.orderedChannels(items)
                items=items.filter {(query.isNotBlank() || selectedCategory=="All" || it.category==selectedCategory) && (query.isBlank() || it.name.contains(query,true) || it.category.contains(query,true))}
                if(prefs.hideDuplicates && selectedSection!="hidden")items=items.distinctBy {it.name.lowercase()}
                items
            }
            displayed=result;adapter.submit(result,Runnable {
                val id=focusedId
                if(id!=null && b.channelGrid.hasFocus()) {
                    val position=adapter.indexOfId(id)
                    if(position>=0){b.channelGrid.scrollToPosition(position);b.channelGrid.post {b.channelGrid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()}}
                }
            })
            if(result.isEmpty())showEmpty(if(query.isNotBlank()) "No matches for \"$query\" in your library." else when(selectedSection) {
                "hidden" -> "No hidden channels. Long-press OK on a channel to manage it."
                "history" -> "Your watch history appears here after playback starts."
                "continue" -> "Movies and episodes in progress will appear here."
                "fav" -> "Long-press OK on a channel to add it to Favorites."
                else -> if(prefs.hasSource())"No matches in this category." else "Add a source, or open a link or video from Tools."
            }) else b.emptyState.visibility=View.GONE
        }
    }
    private fun openItem(ch:Channel) {
        focusedId=ch.id
        if(ch.isSeries) {
            startActivity(Intent(this,SeriesActivity::class.java).putExtra(SeriesActivity.EXTRA_SERIES_ID,ch.id).putExtra(SeriesActivity.EXTRA_TITLE,ch.name))
        } else if(ch.isVod) details(ch)
        else playItems(displayed.filterNot {it.isSeries},displayed.filterNot {it.isSeries}.indexOfFirst {it.id==ch.id})
    }
    private fun details(ch:Channel) {
        val pos=prefs.resumePosition(ch.id)
        val buttons=if(pos>10000)arrayOf("Resume at ${pos/60000} min","Play from beginning","Manage") else arrayOf("Play","Manage")
        val message=listOfNotNull(ch.description,ch.durationSeconds?.let {"${it/60} minutes"},ch.category).joinToString("\n\n")
        AlertDialog.Builder(this).setTitle(ch.name).setMessage(message).setPositiveButton(buttons[0]){_,_->playVod(ch)}
            .setNeutralButton(if(pos>10000)"Start over" else "Manage"){_,_->
                if(pos>10000){prefs.setResumePosition(ch.id,0);playVod(ch)}else contextMenu(ch)
            }.setNegativeButton("Back",null).show()
    }
    private fun playVod(ch:Channel) {
        if(ch.kind!=ContentKind.EPISODE || ch.seriesId==null || ch.providerId!=prefs.activeProfileId){playItems(listOf(ch),0);return}
        lifecycleScope.launch {
            val episodes=try{ContentRepository.loadEpisodes(ch.seriesId)}catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}
            val index=episodes.indexOfFirst{it.id==ch.id}
            if(index>=0)playItems(episodes,index)else playItems(listOf(ch),0)
        }
    }
    private fun playItems(items:List<Channel>,index:Int,multi:Int=0) {
        if(items.isEmpty())return
        stopPreview()
        lifecycleScope.launch {
            val key=withContext(Dispatchers.IO){ContentRepository.createQueue(this@MainActivity,items)}
            startActivity(Intent(this@MainActivity,if(multi>0)MultiViewActivity::class.java else PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_QUEUE,key).putExtra(PlayerActivity.EXTRA_INDEX,index.coerceAtLeast(0)).putExtra(MultiViewActivity.EXTRA_PANES,multi))
        }
    }
    private fun contextMenu(ch:Channel) {
        val items=arrayOf(if(prefs.isFavorite(ch.id))"Remove favorite" else "Add favorite",
            if(prefs.isPinned(ch.id))"Unpin" else "Pin to top",
            if(prefs.isHidden(ch.id))"Unhide" else "Hide",
            "Rename","Change category","Move earlier","Move later","Play now","Remove from history","Mark watched")
        AlertDialog.Builder(this).setTitle(ch.name).setItems(items){_,which->
            when(which) {
                0->prefs.toggleFavorite(ch.id)
                1->prefs.togglePinned(ch.id)
                2->prefs.toggleHidden(ch.id)
                3->{textInput("Channel name",ch.name){prefs.setChannelName(ch.id,it);rebuildCategories()};return@setItems}
                4->{textInput("Category",ch.category){prefs.setChannelCategory(ch.id,it);rebuildCategories()};return@setItems}
                5->prefs.moveChannel(ch.id,-1,displayed.map {it.id})
                6->prefs.moveChannel(ch.id,1,displayed.map {it.id})
                7->{if(!ch.isSeries)playItems(listOf(ch),0)}
                8->prefs.removeHistory(ch.id)
                9->{if(ch.isVod)prefs.recordPlayback(ch,0,0,true)}
            };applyFilter()
        }.show()
    }
    private fun toolsMenu() {
        val options=arrayOf("Open a link","Open a local video / USB","Edit from laptop or phone","Multiview","Join another TV","Sort channels",
            "Refresh library and guide","Playback preferences","Clear watch history","Check for updates","About Red TV")
        AlertDialog.Builder(this).setTitle("Your player").setItems(options){_,n->when(n) {
            0->textInput("Play a video link","https://"){url->
                val parsed=runCatching {java.net.URI(url.trim())}.getOrNull()
                if(parsed?.scheme !in listOf("http","https") || parsed?.host==null)toast("Enter a complete http or https video URL.")
                else AlertDialog.Builder(this).setTitle("Link type").setItems(arrayOf("Movie / video (save resume)","Live stream")){_,type->
                    val ch=Channel("direct_"+Identity.hash(url.trim()),"Direct video",url.trim(),kind=if(type==0)ContentKind.MOVIE else ContentKind.LIVE)
                    playItems(listOf(ch),0)
                }.show()
            }
            1->runCatching {openFile.launch(arrayOf("video/*","audio/*"))}.onFailure {toast("No file picker on this device. Use a laptop video link instead.")}
            2->startActivity(Intent(this,EditOnLaptopActivity::class.java))
            3->multiview()
            4->joinRoom()
            5->{val modes=listOf("custom","az","za","number","category");AlertDialog.Builder(this).setTitle("Sort channels")
                .setSingleChoiceItems(arrayOf("My order / provider order","Name A–Z","Name Z–A","Channel number","Category"),modes.indexOf(prefs.sortMode)){d,i->prefs.sortMode=modes[i];d.dismiss();applyFilter()}.show()}
            6->load()
            7->playbackPreferences()
            8->AlertDialog.Builder(this).setTitle("Clear history for this source?").setMessage("This also clears its saved resume positions.")
                .setPositiveButton("Clear"){_,_->prefs.clearHistory();applyFilter()}.setNegativeButton("Cancel",null).show()
            9->checkUpdate()
            10->AlertDialog.Builder(this).setTitle("Red TV • Your own player").setMessage("Free local player. No account, ads or subscription.\n\nLong-press OK to organize channels. Use Tools → Edit from laptop to manage them on your home network.\n\nMultiview and paired TVs use additional source connections. Device codecs and source permissions determine what plays.").setPositiveButton("OK",null).show()
        }}.show()
    }
    private fun playbackPreferences() {
        stopPreview()
        val checked=booleanArrayOf(prefs.autoPlay,prefs.subtitleEnabled,prefs.hideDuplicates,prefs.channelPreview)
        AlertDialog.Builder(this).setTitle("Playback preferences").setMultiChoiceItems(arrayOf("Autoplay the next episode","Enable subtitles when available","Hide duplicate channel names","Muted channel preview when highlighted"),checked){_,i,value->
            when(i){0->prefs.autoPlay=value;1->prefs.subtitleEnabled=value;2->prefs.hideDuplicates=value;3->prefs.channelPreview=value}
        }.setPositiveButton("Done"){_,_->applyFilter()}.show()
    }
    private fun multiview() {
        val items=prefs.orderedChannels(catalog).filter {it.kind==ContentKind.LIVE && !prefs.isHidden(it.id)}
        if(items.size<2){toast("Add at least two live channels first.");return}
        val chosen=linkedSetOf<String>()
        val search=EditText(this).apply {hint="Filter channels"}
        val list=android.widget.ListView(this).apply {choiceMode=android.widget.ListView.CHOICE_MODE_MULTIPLE}
        var visible=items
        fun update() {
            visible=items.filter {it.name.contains(search.text.toString(),true)}
            list.adapter=android.widget.ArrayAdapter(this,android.R.layout.simple_list_item_multiple_choice,visible.map {it.name})
            visible.forEachIndexed {i,ch->list.setItemChecked(i,ch.id in chosen)}
        }
        search.addTextChangedListener(object:TextWatcher {
            override fun afterTextChanged(s:Editable?){update()}
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
        })
        list.setOnItemClickListener {_,_,i,_->val id=visible[i].id
            if(id in chosen)chosen.remove(id) else if(chosen.size<4)chosen.add(id) else {list.setItemChecked(i,false);toast("Choose up to four channels.")}
        }
        update()
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;addView(search);addView(list,LinearLayout.LayoutParams(-1,320))}
        val dialog=AlertDialog.Builder(this).setTitle("Multiview • choose 2–4 channels").setView(box)
            .setPositiveButton("Watch",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(chosen.size<2)toast("Choose at least two channels.") else {
                val selected=chosen.mapNotNull {id->items.firstOrNull {it.id==id}}
                dialog.dismiss();playItems(selected,0,if(selected.size==2)2 else 4)
            }
        }}
        dialog.show()
    }
    private fun joinRoom() {
        val address=EditText(this).apply {hint="Host TV URL, e.g. http://192.168.1.20:8091";inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI}
        val code=EditText(this).apply {hint="Six-digit code";inputType=android.text.InputType.TYPE_CLASS_NUMBER}
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(32,8,32,8);addView(address);addView(code)}
        AlertDialog.Builder(this).setTitle("Join a TV on your home network")
            .setMessage("On the other TV, open player Options → Share to another TV. Pairing shares that video's source link with this TV. Live sync depends on the stream's timing information.")
            .setView(box).setPositiveButton("Join"){_,_->
                lifecycleScope.launch {
                    try {val url=address.text.toString().trim();val pin=code.text.toString().trim()
                        val token=withContext(Dispatchers.IO){RoomSyncClient(url).pair(pin)}
                        startActivity(Intent(this@MainActivity,PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_ROOM_URL,url).putExtra(PlayerActivity.EXTRA_ROOM_TOKEN,token))
                    }catch(e:CancellationException){throw e}catch(_:Exception){toast("Couldn't pair. Check the TV address, code and home network.")}
                }
            }.setNegativeButton("Cancel",null).show()
    }
    private fun sources() {
        val profiles=prefs.profiles()
        val names=profiles.map { (if(it.id==prefs.activeProfileId)"● " else "")+it.name } + "＋ Add source"
        AlertDialog.Builder(this).setTitle("Sources").setItems(names.toTypedArray()){_,which->
            if(which==profiles.size)startActivity(Intent(this,SetupActivity::class.java).putExtra(SetupActivity.EXTRA_NEW,true))
            else {
                val profile=profiles[which]
                AlertDialog.Builder(this).setTitle(profile.name).setItems(arrayOf("Use this source","Edit on TV","Delete source")){_,action->
                    when(action) {
                        0->{prefs.activeProfileId=profile.id;category="All";load()}
                        1->{prefs.activeProfileId=profile.id;ContentRepository.dirty=true;startActivity(Intent(this,SetupActivity::class.java))}
                        2->AlertDialog.Builder(this).setTitle("Delete ${profile.name}?").setPositiveButton("Delete"){_,_->prefs.deleteProfile(profile.id);load()}.setNegativeButton("Cancel",null).show()
                    }
                }.show()
            }
        }.show()
    }
    private fun checkUpdate() {
        lifecycleScope.launch {
            try {
                val info=Updater.check()
                if(info==null){toast("No newer signed release is available.");return@launch}
                AlertDialog.Builder(this@MainActivity).setTitle("Update available").setMessage(info.label).setPositiveButton("Download"){_,_->
                    lifecycleScope.launch {try{Updater.downloadAndInstall(this@MainActivity,info.apkUrl)}catch(_:Exception){toast("Update could not be installed. Check connection and installation permission.")}}
                }.setNegativeButton("Later",null).show()
            }catch(_:Exception){toast("Couldn't check for updates. Try again later.")}
        }
    }
    private fun textInput(title:String,initial:String,save:(String)->Unit) {
        val input=EditText(this).apply {setText(initial);setSingleLine();selectAll()}
        AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("OK"){_,_->save(input.text.toString())}.setNegativeButton("Cancel",null).show()
    }
    private fun showEmpty(message:String){b.emptyState.text=message;b.emptyState.visibility=View.VISIBLE}
    private fun toast(message:String){Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
}


