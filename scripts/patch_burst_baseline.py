import io

path = 'app/src/androidTest/java/tw/personal/privateinbox/BurstCaptureInstrumentedTest.kt'
s = io.open(path, encoding='utf-8').read()

old = """    /**
     * The metric report for one run.
     *
     * The unaccounted count is the important one: it is the number of messages
     * the listener represented that never reached the database and were not
     * recognised as duplicates.
     */"""
new = """    /**
     * The counters at the start of this test's measurement window.
     *
     * The metrics live in the APP's process and are CUMULATIVE: every
     * instrumentation test runs against the same process, so a reset would
     * wipe the counters of a test that is measuring at the same time. The
     * burst matrix therefore captures a baseline and reports the DIFFERENCE.
     */
    private var metricBaseline: Map<String, Long> = emptyMap()

    /**
     * The metric report for one run, as the difference from the baseline.
     *
     * The unaccounted count is the important one: it is the number of messages
     * the listener represented that never reached the database and were not
     * recognised as duplicates.
     */"""
assert old in s, 'report doc not found'
s = s.replace(old, new)

old2 = """        val m = CaptureMetrics.snapshot()"""
new2 = """        val raw = CaptureMetrics.snapshot()
        // Report the difference from this test's baseline, so a test that
        // shares the process with other tests still reports ITS OWN numbers.
        val m = raw.mapValues { (key, value) -> value - (metricBaseline[key] ?: 0L) }"""
assert old2 in s, 'snapshot call not found'
s = s.replace(old2, new2)

count = s.count("        val reached = waitUntil(")
assert count >= 2, "expected several measurement windows, found %d" % count
s = s.replace("        val reached = waitUntil(",
              "        metricBaseline = CaptureMetrics.snapshot()\n"
              "        val reached = waitUntil(")

io.open(path, 'w', encoding='utf-8').write(s)
print("the burst report now subtracts its own baseline (%d windows)" % count)
