package com.fantadrom.jjreader.engine

import android.os.Parcel
import android.os.Parcelable
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates

class MarginMark : Decoration.Style {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<MarginMark> {
            override fun createFromParcel(source: Parcel) = MarginMark()
            override fun newArray(size: Int): Array<MarginMark?> = arrayOfNulls(size)
        }
    }
}
/** Explicit stacking makes overlap colors independent of insertion/creation order. */
data class NoteHighlight(val color:Int,val layer:Int,val line:Boolean=false,val alpha:Float=.38f):Decoration.Style {
    override fun describeContents()=0
    override fun writeToParcel(dest:Parcel,flags:Int){dest.writeInt(color);dest.writeInt(layer);dest.writeInt(if(line)1 else 0);dest.writeFloat(alpha)}
    companion object {
        @JvmField val CREATOR=object:Parcelable.Creator<NoteHighlight>{
            override fun createFromParcel(source:Parcel)=NoteHighlight(source.readInt(),source.readInt(),source.readInt()==1,source.readFloat())
            override fun newArray(size:Int):Array<NoteHighlight?> = arrayOfNulls(size)
        }
    }
}
object ReaderDecorations {
    const val BLUE: Int = -14522416 // #2267d0
    fun templates() = HtmlDecorationTemplates.defaultTemplates().apply {
        this[Decoration.Style.Underline::class] = HtmlDecorationTemplate.underline(BLUE, 3, 0, 1.0)
        this[NoteHighlight::class]=HtmlDecorationTemplate(
            layout=HtmlDecorationTemplate.Layout.BOXES,width=HtmlDecorationTemplate.Width.WRAP,
            element={decoration->val s=decoration.style as NoteHighlight;val r=s.color shr 16 and 255;val g=s.color shr 8 and 255;val b=s.color and 255
                if(s.line)"<div style='box-sizing:border-box;border-bottom:1px solid rgb($r,$g,$b);z-index:${s.layer}'></div>"else "<div class='jj-note-highlight' style='background-color:rgba($r,$g,$b,${s.alpha.coerceIn(0f,.38f)})!important;mix-blend-mode:multiply;z-index:${s.layer}'></div>"},
            stylesheet="html[style*='readium-night-on'] .jj-note-highlight{mix-blend-mode:screen!important}"
        )
        this[MarginMark::class] = HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOUNDS, width = HtmlDecorationTemplate.Width.PAGE,
            element = { "<div><span data-activable='1' class='jj-note' role='button' aria-label='Комментарий'>●</span></div>" },
            stylesheet = ".jj-note{position:absolute;left:3px;top:0;width:20px;height:24px;color:#8a4800!important;font-size:18px!important;line-height:24px!important;}"
        )
    }
}
