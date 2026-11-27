package com.ac.acrpc.fault;

import com.ac.acrpc.model.ServiceMetaInfo;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class OutlierDetectorTest {

    private final AtomicLong now = new AtomicLong(-5_000_000_000L); // nanoTime may be negative
    private final OutlierDetector detector = new OutlierDetector(3, 1000, 4000, now::get);
    private final ServiceMetaInfo a = node(1);
    private final ServiceMetaInfo b = node(2);
    private final List<ServiceMetaInfo> both = List.of(a, b);

    @Test
    public void ejectsAfterConsecutiveFailures() {
        detector.onFailure(a);
        detector.onFailure(a);
        assertEquals(both, detector.available(both));
        detector.onFailure(a);
        assertEquals(List.of(b), detector.available(both));
    }

    @Test
    public void successResetsTheFailureCount() {
        detector.onFailure(a);
        detector.onFailure(a);
        detector.onSuccess(a);
        detector.onFailure(a);
        assertEquals(both, detector.available(both));
    }

    @Test
    public void ejectionExpiresAndDoublesEachTime() {
        fail(a, 3);
        advanceMs(999);
        assertEquals(List.of(b), detector.available(both));
        advanceMs(1);
        assertEquals(both, detector.available(both));

        fail(a, 3);
        advanceMs(1999);
        assertEquals(List.of(b), detector.available(both));
        advanceMs(1);
        assertEquals(both, detector.available(both));

        fail(a, 3); // 4 s
        fail(a, 0);
        advanceMs(4000);
        fail(a, 3); // capped at 4 s, not 8
        advanceMs(4000);
        assertEquals(both, detector.available(both));
    }

    @Test
    public void failuresWhileEjectedDoNotExtendTheEjection() {
        fail(a, 3);
        advanceMs(500);
        fail(a, 10);
        advanceMs(500);
        assertEquals(both, detector.available(both));
    }

    @Test
    public void neverEjectsEveryCandidate() {
        fail(a, 3);
        fail(b, 3);
        assertEquals(both, detector.available(both));
    }

    private void fail(ServiceMetaInfo node, int times) {
        for (int i = 0; i < times; i++) {
            detector.onFailure(node);
        }
    }

    private void advanceMs(long ms) {
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    private static ServiceMetaInfo node(int i) {
        ServiceMetaInfo info = new ServiceMetaInfo();
        info.setServiceName("svc");
        info.setServiceHost("10.0.0." + i);
        info.setServicePort(8080);
        return info;
    }
}
