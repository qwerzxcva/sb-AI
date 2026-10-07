package com.sbai

import com.sbai.service.NodeLatencyResults
import com.sbai.service.NodeLatencyResults.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeLatencyResultsTest {
    private val now = 1_750_000_005_000L

    @Test fun unchangedDelayWithNewTimestampIsFresh() {
        assertEquals(40, NodeLatencyResults.freshDelay(Sample(40, now - 1000), Sample(40, now), now))
    }

    @Test fun changedDelayWithoutNewTimestampIsStale() {
        assertNull(NodeLatencyResults.freshDelay(Sample(40, now), Sample(80, now), now))
    }

    @Test fun olderOrMissingSamplesAreNotFailures() {
        assertNull(NodeLatencyResults.freshDelay(Sample(40, now), Sample(80, now - 1000), now))
        assertNull(NodeLatencyResults.freshDelay(null, null, now))
        assertNull(NodeLatencyResults.freshDelay(null, Sample(80, 0), now))
    }

    @Test fun failureAndZeroNeverBecomePersistableLatency() {
        assertNull(NodeLatencyResults.freshDelay(null, Sample(-1, now), now))
        assertNull(NodeLatencyResults.freshDelay(null, Sample(0, now), now))
    }

    @Test fun secondsAndMillisecondsAreSupported() {
        assertEquals(80, NodeLatencyResults.freshDelay(null, Sample(80, now / 1000), now))
        assertEquals(80, NodeLatencyResults.freshDelay(null, Sample(80, now), now))
    }

    @Test fun newlyDiscoveredHistoricalCacheIsRejected() {
        assertNull(NodeLatencyResults.freshDelay(null, Sample(80, now - 1000), now))
        assertNull(NodeLatencyResults.freshDelay(null, Sample(80, now / 1000 - 1), now))
    }
}
