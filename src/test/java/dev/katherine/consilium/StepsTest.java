package dev.katherine.consilium;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepsTest {
    @Test
    void aStepWhoseWorkThrowsStillEnds() {
        AtomicInteger announced = new AtomicInteger();
        Steps steps = new Steps(Runnable::run, Runnable::run, announced::incrementAndGet);

        steps.run(() -> {
            throw new IllegalStateException("boom");
        }, ignored -> {
        });

        assertTrue(steps.isEmpty(), "the room isn't left busy");
        assertEquals(1, announced.get());
    }
}
