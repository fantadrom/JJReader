package com.fantadrom.jjreader

import com.fantadrom.jjreader.engine.AndroidXhtmlValidator
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AndroidXhtmlValidatorTest {
    @Test fun correctNamespacesUnicodeEntitiesAndProvider() {
        AndroidXhtmlValidator.validate("<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops' xml:lang='ru'><body><p epub:type='note'>Ёж 😀 &amp; &#x1F600; &#38;</p><br/></body></html>"){println("XML provider (Robolectric, NOT device): $it");assertFalse(it.contains("xerces",true))}
    }
    @Test fun malformedUnsafeAndUndefinedInputsAreRejected() {
        for(xml in listOf("<a><b></a>","<a><p:b/></a>","<a>&unknown;</a>","<a>&#0;</a>","<a/ ><b/>","<a/><b/>","<!DOCTYPE a SYSTEM 'file:///private'><a/>","<!DOCTYPE a [<!ENTITY x SYSTEM 'https://invalid.example/private'>]><a>&x;</a>","<!DOCTYPE a [<!ENTITY x 'SECRET'>]><a>&x;</a>")) {
            assertTrue("Synthetic input must fail: $xml",runCatching{AndroidXhtmlValidator.validate(xml)}.isFailure)
        }
    }
}
