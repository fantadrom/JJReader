package com.fantadrom.jjreader.notes

enum class NoteColor(val key:String,val label:String,val rgb:Int) {
    RED("red","Красный",0xFFF28B82.toInt()),BLUE("blue","Синий",0xFF8AB4F8.toInt()),
    GREEN("green","Зелёный",0xFF81C995.toInt()),YELLOW("yellow","Жёлтый",0xFFFDD663.toInt()),
    PURPLE("purple","Фиолетовый",0xFFC58AF9.toInt());
    companion object { fun of(key:String?)=entries.firstOrNull{it.key==key} }
}
