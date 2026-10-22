import io

path = 'app/src/androidTest/java/tw/personal/privateinbox/LatencyBenchmarkInstrumentedTest.kt'
s = io.open(path, encoding='utf-8').read()

old = """        // The metrics live in the APP's process, so they are reset through the
        // provider rather than in this process.
        BenchmarkSetup.resetMetrics()
    }"""

new = """        // The metrics live in the APP's process, so they are reset through the
        // provider rather than in this process.
        //
        // The reset happens in setUp, which runs BEFORE the measurement
        // window. But the instrumentation tests share the app's process, so
        // another test that resets the metrics while this one is measuring
        // would wipe its samples. The reset is therefore the LAST thing
        // before this test's own measurement, and the samples are read
        // immediately after it.
        BenchmarkSetup.resetMetrics()
    }"""
assert old in s, 'the reset call was not found'
s = s.replace(old, new)

io.open(path, 'w', encoding='utf-8').write(s)
print("the latency test documents the shared-process reset")
