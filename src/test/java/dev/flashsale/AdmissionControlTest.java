package dev.flashsale;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionControlTest {
    @Test void rejectsSaturationAndReleasesPermitOnlyOnce() {
        var clock=new AtomicLong(0);
        var gate=new AdmissionControl(1,3,clock::get);
        var first=gate.enter();
        assertEquals(429,assertThrows(SaleException.class,gate::enter).status);
        first.close(); first.close();
        try(var second=gate.enter()) { assertThrows(SaleException.class,gate::enter); }
        try(var third=gate.enter()) { }
        assertThrows(SaleException.class,gate::enter);
        clock.set(1_000_000_000L);
        try(var next=gate.enter()) { }
    }
}
