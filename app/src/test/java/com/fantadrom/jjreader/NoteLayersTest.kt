package com.fantadrom.jjreader

import com.fantadrom.jjreader.notes.NoteLayers
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NoteLayersTest {
    @Test fun aggregateOpacityIsBoundedAndTouchingResourcesDoNotCountAsOverlap(){
        fun note(id:String,r:Int,a:Int,b:Int)=JSONObject().put("id",id).put("chapter",r).put("start",a).put("end",b).put("color","blue")
        val notes=listOf(note("a",0,0,10),note("b",0,10,20),note("c",0,5,15),note("other",1,0,50))+ (0..4).map{note("same-$it",0,30,40)}
        val depths=NoteLayers.depth(notes);assertEquals(2,depths["a"]);assertEquals(2,depths["b"]);assertEquals(2,depths["c"]);assertEquals(1,depths["other"]);assertEquals(5,depths["same-0"])
        for(x in 0..45){val opacity=notes.filter{it.getInt("chapter")==0&&x>=it.getInt("start")&&x<it.getInt("end")}.sumOf{.38/checkNotNull(depths[it.getString("id")])};assertTrue(opacity<=.380001)}
        assertEquals(depths,NoteLayers.depth(notes.reversed()))
        assertEquals(1,NoteLayers.depth(notes.take(2))["a"])
    }
}
