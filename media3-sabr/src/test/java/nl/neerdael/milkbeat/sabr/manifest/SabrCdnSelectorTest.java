package nl.neerdael.milkbeat.sabr.manifest;

import static org.junit.Assert.*;
import org.junit.Test;

public class SabrCdnSelectorTest {
    @Test public void alternateNetworksRetainRedirectorShardAndSignedUrlBytes() {
        for(String prefix:new String[]{"rr5---","rr12---"}) {
            String initial="https://"+prefix+"sn-a.googlevideo.com:443/videoplayback?mn=sn-a%2Csn-b%2Csn-c&signature=fixture%2Bvalue&rn=7";
            SabrCdnSelector selector=new SabrCdnSelector(initial);
            assertTrue(selector.maybeAdvance(initial));
            String second=initial.replace("sn-a.googlevideo.com","sn-b.googlevideo.com");
            assertEquals(second,selector.getCurrentUrl());
            assertTrue(selector.maybeAdvance(initial));assertEquals(second,selector.getCurrentUrl());
            assertTrue(selector.maybeAdvance(second));
            String third=initial.replace("sn-a.googlevideo.com","sn-c.googlevideo.com");
            assertEquals(third,selector.getCurrentUrl());assertFalse(selector.maybeAdvance(third));
        }
    }
}
