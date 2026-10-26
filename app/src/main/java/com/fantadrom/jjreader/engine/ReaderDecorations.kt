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
object ReaderDecorations {
    const val BLUE: Int = -14522416 // #2267d0
    fun templates() = HtmlDecorationTemplates.defaultTemplates().apply {
        this[Decoration.Style.Underline::class] = HtmlDecorationTemplate.underline(BLUE, 3, 0, 1.0)
        this[MarginMark::class] = HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOUNDS, width = HtmlDecorationTemplate.Width.PAGE,
            element = { "<div><span data-activable='1' class='jj-note' role='button' aria-label='Комментарий'>●</span></div>" },
            stylesheet = ".jj-note{position:absolute;left:3px;top:0;width:20px;height:24px;color:#8a4800!important;font-size:18px!important;line-height:24px!important;}"
        )
    }
}
