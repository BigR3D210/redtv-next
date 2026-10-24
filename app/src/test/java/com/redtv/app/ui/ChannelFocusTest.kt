package com.redtv.app.ui

import android.app.Activity
import android.widget.FrameLayout
import com.redtv.app.data.Prefs
import com.redtv.app.model.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ChannelFocusTest {
    @Test fun focusedTileRebindReportsTheChannelSoPreviewCanResume() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val root=FrameLayout(activity);activity.setContentView(root)
        val events=mutableListOf<Channel?>()
        val adapter=ChannelAdapter(Prefs(activity),{_,_->},{},{events.add(it)})
        val channel=Channel("a","Channel A","https://example.test/a")
        adapter.submitList(listOf(ChannelRow(channel,false,"Now")))
        val holder=adapter.onCreateViewHolder(root,0)
        root.addView(holder.itemView)
        adapter.onBindViewHolder(holder,0)
        assertTrue(holder.itemView.requestFocus())
        events.clear()
        adapter.onBindViewHolder(holder,0)
        assertEquals(channel,events.lastOrNull())
        activity.finish()
    }
    @Test fun stableIdentitySurvivesAResultReorder() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val adapter=ChannelAdapter(Prefs(activity),{_,_->},{})
        val a=ChannelRow(Channel("a","A","https://example.test/a"),false,"")
        val b=ChannelRow(Channel("b","B","https://example.test/b"),false,"")
        adapter.submitList(listOf(a,b))
        assertTrue(adapter.hasStableIds())
        val id=adapter.getItemId(0)
        adapter.submitList(listOf(b,a))
        assertEquals(id,adapter.getItemId(1))
        assertNotEquals(adapter.getItemId(0),adapter.getItemId(1))
        activity.finish()
    }
}
