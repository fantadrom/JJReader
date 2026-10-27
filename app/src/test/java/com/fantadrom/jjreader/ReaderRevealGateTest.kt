@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.fantadrom.jjreader

import android.view.View
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.ui.ReaderStage
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ReaderRevealGateTest {
    @Test fun coverSurvivesUntilVisualAndPreDrawSignalsWithoutFixedDelay()=runTest {
        val stage=ReaderStage(RuntimeEnvironment.getApplication(),23001);val events=mutableListOf<String>();val visual=CompletableDeferred<Unit>();val draw=CompletableDeferred<Unit>()
        val gate=ReaderRevealGate(stage,{null},{event,_->events+=event},ReaderFrames{_,valid,onVisual->visual.await();check(valid());onVisual();draw.await()})
        gate.begin(1);val pending=launch{gate.present(1,"r1.xhtml"){true}};runCurrent()
        advanceTimeBy(2000);runCurrent();assertEquals(View.VISIBLE,stage.cover.visibility);assertEquals(listOf("PRESENT_HIDE"),events)
        visual.complete(Unit);runCurrent();assertEquals(View.VISIBLE,stage.cover.visibility);assertEquals("PRESENT_VISUAL_READY",events.last())
        draw.complete(Unit);pending.join();assertEquals(View.GONE,stage.cover.visibility);assertEquals(listOf("PRESENT_HIDE","PRESENT_VISUAL_READY","PRESENT_REVEAL"),events)
    }
    @Test fun lateOldFrameCannotUncoverNewDocumentOrRevision()=runTest {
        val stage=ReaderStage(RuntimeEnvironment.getApplication(),23001);val signal=CompletableDeferred<Unit>();val events=mutableListOf<String>()
        val gate=ReaderRevealGate(stage,{null},{event,_->events+=event},ReaderFrames{_,_,onVisual->signal.await();onVisual()})
        gate.begin(0);val old=launch{gate.present(0,"r0.xhtml"){true}};runCurrent();gate.begin(1);signal.complete(Unit);old.join()
        assertTrue(old.isCancelled);assertEquals(View.VISIBLE,stage.cover.visibility);assertTrue(gate.pending);assertFalse(events.contains("PRESENT_REVEAL"))
    }
    @Test fun timeoutKeepsCoverAndVerifiedRecoveryCanRevealDifferentCancelledTarget()=runTest {
        val stage=ReaderStage(RuntimeEnvironment.getApplication(),23001)
        val stuck=ReaderRevealGate(stage,{null},{_,_->},ReaderFrames{_,_,_->awaitCancellation()})
        stuck.begin(1);val pending=launch{stuck.present(1,"r1.xhtml"){true}};advanceUntilIdle();pending.join()
        assertTrue(pending.isCancelled);assertTrue(stuck.failed);assertEquals(View.VISIBLE,stage.cover.visibility)
        val events=mutableListOf<Pair<String,Int>>()
        val recovering=ReaderRevealGate(stage,{null},{event,index->events+=event to index},ReaderFrames{_,valid,visual->check(valid());visual()})
        recovering.begin(1);recovering.present(0,"r0.xhtml"){true}
        assertEquals("PRESENT_REVEAL" to 0,events.last());assertEquals(View.GONE,stage.cover.visibility)
    }

}
