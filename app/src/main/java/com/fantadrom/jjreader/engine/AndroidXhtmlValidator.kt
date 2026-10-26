package com.fantadrom.jjreader.engine

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

class XhtmlValidationException(val code:String,cause:Exception):IllegalStateException(code,cause)

/** Android API31+ parser, no JAXP provider features or external resolver. */
object AndroidXhtmlValidator {
    fun validate(xhtml:String, provider:(String)->Unit={}) {
        val parser=try {
            Xml.newPullParser().also {
                provider(it.javaClass.name)
                it.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true)
            }
        }catch(e:Exception){throw XhtmlValidationException("XML_PARSER_CONFIGURATION",e)}
        // Reject declarations before the parser can process any entity declarations.
        if(xhtml.contains("<!DOCTYPE") || xhtml.contains("<!ENTITY"))throw XhtmlValidationException("XML_DTD_FORBIDDEN",XmlPullParserException("DTD_FORBIDDEN"))
        fun validCharacters(text:String) {
            var offset=0
            while(offset<text.length) {
                val cp=Character.codePointAt(text,offset)
                if(!(cp==9 || cp==10 || cp==13 || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF))throw XmlPullParserException("XML_CHARACTER")
                offset+=Character.charCount(cp)
            }
        }
        try {
            validCharacters(xhtml)
            parser.setInput(xhtml.reader())
            var roots=0
            while(true) {
                when(parser.nextToken()) {
                    XmlPullParser.START_TAG -> {
                        if(parser.depth==1)roots++
                        for(i in 0 until parser.attributeCount)validCharacters(parser.getAttributeValue(i))
                    }
                    XmlPullParser.DOCDECL -> throw XmlPullParserException("DTD_FORBIDDEN")
                    XmlPullParser.ENTITY_REF -> validCharacters(parser.text ?: throw XmlPullParserException("ENTITY_UNDEFINED"))
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            if(roots!=1)throw XmlPullParserException("ROOT_REQUIRED")
        }catch(e:Exception){throw XhtmlValidationException("XML_INVALID",e)}
    }
}
