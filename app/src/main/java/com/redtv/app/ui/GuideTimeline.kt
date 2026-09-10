package com.redtv.app.ui

import com.redtv.app.model.EpgProgram

object GuideTimeline {
    data class Slot(val start:Long,val end:Long,val program:EpgProgram?)
    fun slots(programs:List<EpgProgram>,start:Long,end:Long):List<Slot> {
        require(end>start)
        val result=mutableListOf<Slot>();var cursor=start
        programs.sortedBy {it.startMillis}.forEach {p->
            val a=maxOf(cursor,p.startMillis,start);val b=minOf(end,p.stopMillis)
            if(b>a){if(a>cursor)result.add(Slot(cursor,a,null));result.add(Slot(a,b,p));cursor=b}
        }
        if(cursor<end)result.add(Slot(cursor,end,null))
        return result
    }
}
