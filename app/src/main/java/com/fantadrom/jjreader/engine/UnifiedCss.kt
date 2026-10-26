package com.fantadrom.jjreader.engine

/** Conservative scoped CSS, independent of publisher class/ID collisions. Not a full CSS engine. */
internal class UnifiedCss(private val url:(String,String)->String,private val read:(String)->String?) {
    private val fonts=mutableMapOf<String,String>()
    private val loading=mutableSetOf<String>()
    fun load(path:String,index:Int,bodyId:String):String {
        if(!loading.add(path))throw UnifiedUnsupported("циклический CSS import")
        return try{transform(read(path) ?: throw UnifiedUnsupported("локальный CSS не найден"),path,index,bodyId)}finally{loading.remove(path)}
    }
    fun declarations(value:String,base:String):String {
        if(Regex("(?i)(?:position\\s*:\\s*(?:absolute|fixed)|writing-mode\\s*:[^;]*(?:vertical|sideways))").containsMatchIn(value))throw UnifiedUnsupported("позиционная или вертикальная CSS-вёрстка")
        var safe=value.replace(Regex("(?i)(?:page-break-(?:before|after|inside)|break-(?:before|after|inside)|column-(?:count|width)|-webkit-column-(?:count|width))\\s*:[^;]*(?:;|$)"),"")
        safe=Regex("(?i)url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)").replace(safe){m->"url(\"${url(base,m.groupValues[2]).replace("\"","%22")}\")"}
        return safe
    }
    private fun selectors(value:String):List<String> {
        val parts=mutableListOf<String>();var began=0;var depth=0;var quote:Char?=null
        value.forEachIndexed{i,c->
            if(quote!=null){if(c==quote && (i==0 || value[i-1]!='\\'))quote=null}
            else when(c){'\'','"'->quote=c;'(', '['->depth++;')',']'->depth--;','->if(depth==0){parts+=value.substring(began,i);began=i+1}}
        };parts+=value.substring(began);return parts
    }
    fun transform(input:String,base:String,index:Int,bodyId:String):String {
        val css=input.replace(Regex("/\\*[\\s\\S]*?\\*/"),"");val output=StringBuilder();var cursor=0
        while(cursor<css.length){
            while(cursor<css.length && css[cursor].isWhitespace())cursor++
            if(cursor==css.length)break
            val start=cursor;var quote:Char?=null;var parens=0
            while(cursor<css.length){val c=css[cursor];if(quote!=null){if(c==quote && (cursor==0 || css[cursor-1]!='\\'))quote=null}else when(c){'\'','"'->quote=c;'('->parens++;')'->parens--;'{',';'->if(parens==0)break};cursor++}
            if(cursor>=css.length)break
            val header=css.substring(start,cursor).trim()
            if(css[cursor]==';'){
                if(header.startsWith("@import",true)){
                    val path=Regex("(?i)^@import\\s+(?:url\\(\\s*)?['\"]([^'\"]+)['\"]\\s*\\)?\\s*(.*)$").find(header) ?: throw UnifiedUnsupported("неподдерживаемый CSS import")
                    val resolved=java.net.URI(null,null,base,null).resolve(path.groupValues[1]).normalize()
                    if(resolved.isAbsolute || resolved.path.startsWith("../"))throw UnifiedUnsupported("внешний CSS import")
                    val imported=load(resolved.path,index,bodyId);val media=path.groupValues[2]
                    output.append(if(media.isBlank())imported else "@media $media{$imported}")
                }else if(header.startsWith("@namespace",true))output.append(header).append(';')
                cursor++;continue
            }
            cursor++;val bodyStart=cursor;var depth=1;quote=null
            while(cursor<css.length && depth>0){val c=css[cursor];if(quote!=null){if(c==quote && css[cursor-1]!='\\')quote=null}else when(c){'\'','"'->quote=c;'{'->depth++;'}'->depth--};cursor++}
            if(depth!=0)throw UnifiedUnsupported("неполный CSS")
            val body=css.substring(bodyStart,cursor-1)
            when {
                header.startsWith("@media",true) || header.startsWith("@supports",true)->output.append(header).append('{').append(transform(body,base,index,bodyId)).append('}')
                header.startsWith("@font-face",true)->{
                    fun property(name:String)=Regex("(?i)$name\\s*:\\s*([^;]+)").find(body)?.groupValues?.get(1)?.trim().orEmpty()
                    val key=listOf(property("font-family"),property("font-weight"),property("font-style")).joinToString("|")
                    val declaration=declarations(body,base)
                    if(fonts[key]!=null && fonts[key]!=declaration)throw UnifiedUnsupported("конфликт font-face в разделах")
                    if(fonts.put(key,declaration)==null)output.append(header).append('{').append(declaration).append('}')
                }
                header.startsWith("@page",true)->Unit // Pagination has no meaning in the continuous vertical rendition.
                header.startsWith('@')->throw UnifiedUnsupported("CSS at-rule вне базового оформления")
                else->{
                    if(header.contains('\\'))throw UnifiedUnsupported("экранированный CSS-селектор")
                    val selectors=selectors(header).flatMap{raw->
                        var selector=Regex("#([\\p{L}_][\\p{L}\\p{N}_-]*)").replace(raw.trim()){m->"#jj-$index-${m.groupValues[1]}"}
                        selector=selector.replace(Regex("(?i)^html(?:\\s*>?\\s*body)?\\b"),"#$bodyId").replace(Regex("(?i)^body\\b|^:root\\b"),"#$bodyId")
                        if(selector.contains("#$bodyId"))listOf(selector)
                        else if(selector.startsWith('.') || selector.startsWith('[') || selector.startsWith(':'))listOf("#$bodyId $selector","#$bodyId$selector")
                        else listOf("#$bodyId $selector")
                    }
                    output.append(selectors.joinToString(",")).append('{').append(declarations(body,base)).append('}')
                }
            }
        }
        return output.toString()
    }
}
