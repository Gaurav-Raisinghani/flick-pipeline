package com.flik.common.constants;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueueConstantsTest {

    @Test
    void routingKeyForPriority_returnsCorrectKeys() {
        assertEquals("task.p0", QueueConstants.routingKeyForPriority(0));
        assertEquals("task.p1", QueueConstants.routingKeyForPriority(1));
        assertEquals("task.p2", QueueConstants.routingKeyForPriority(2));
    }

    @Test
    void routingKeyForPriority_unknownPriorityDefaultsToP2() {
        assertEquals("task.p2", QueueConstants.routingKeyForPriority(99));
        assertEquals("task.p2", QueueConstants.routingKeyForPriority(-1));
    }

    @Test
    void queueForPriority_returnsCorrectQueues() {
        assertEquals("flik.tasks.p0", QueueConstants.queueForPriority(0));
        assertEquals("flik.tasks.p1", QueueConstants.queueForPriority(1));
        assertEquals("flik.tasks.p2", QueueConstants.queueForPriority(2));
    }

    @Test
    void queueForPriority_unknownPriorityDefaultsToP2() {
        assertEquals("flik.tasks.p2", QueueConstants.queueForPriority(99));
    }

    @Test
    void maxRetryCount_isThree() {
        assertEquals(3, QueueConstants.MAX_RETRY_COUNT);
    }

    @Test
    void maxPriority_isTen() {
        assertEquals(10, QueueConstants.MAX_PRIORITY);
    }
}
