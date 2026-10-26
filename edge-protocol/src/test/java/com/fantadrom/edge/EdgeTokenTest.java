package com.fantadrom.edge;
import org.junit.Test;
import static org.junit.Assert.*;

public class EdgeTokenTest {
    // Independent upstream Python outputs, fixed timestamps; no service calls or user content.
    @Test public void v2MatchesReferenceAtThreeTimes() {
        assertEquals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6", EdgeToken.generate(0));
        assertEquals("6D50DDDB91812C33-DD68EFB01898B0750C9797D51A5FF93471DFDAE6888A1039D532CBDCCE54545D", EdgeToken.generate(1760000000));
        assertEquals("F1A2480FB13757AD-55D67626C17347409BE583A9AD95A44BE5E620B21471657C91CE388C32CBCC50", EdgeToken.generate(1792918800));
    }
    @Test public void usesFiveMinuteWindows() {
        assertEquals(EdgeToken.generate(0), EdgeToken.generate(299));
        assertNotEquals(EdgeToken.generate(299), EdgeToken.generate(300));
    }
}
