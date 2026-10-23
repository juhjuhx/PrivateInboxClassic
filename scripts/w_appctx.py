import io

path = 'app/src/main/java/tw/personal/privateinbox/PrivateNotificationListener.kt'
s = io.open(path, encoding='utf-8').read()

# The submit block must use the CONTEXT THAT WAS PASSED IN, not the service
# property. The service's applicationContext is null in an instance that the
# system did not construct.
old = """        val accepted = submit {
            if (!LocalSettings.isRecording(applicationContext)) return@submit
            if (!CapturePolicy.allowed(pkg, LocalSettings.selected(applicationContext))) return@submit"""
new = """        val accepted = submit {
            // The context that was PASSED IN, not the service's own
            // applicationContext. The service property is null in an instance
            // that the system did not construct -- which is what the catch-up
            // path and the tests do -- and a null context made the whole
            // capture silently produce nothing.
            if (!LocalSettings.isRecording(appContext)) return@submit
            if (!CapturePolicy.allowed(pkg, LocalSettings.selected(appContext))) return@submit"""
assert old in s, 'the submit block was not found'
s = s.replace(old, new)

io.open(path, 'w', encoding='utf-8').write(s)
print("the submit block uses the passed-in context")
