import tw.personal.privateinbox.CapturePolicy

fun main() {
    val line = "jp.naver.line.android"
    val ig = "com.instagram.android"
    check(!CapturePolicy.allowed(line, emptySet()))
    check(CapturePolicy.allowed(line, setOf(line)))
    check(!CapturePolicy.allowed(ig, setOf(line)))
    check(!CapturePolicy.allowed("com.example.bank", setOf("com.example.bank")))
    check(CapturePolicy.normalize("  你好   世界 \r\n") == "你好 世界")
    check(CapturePolicy.normalize("   ") == null)
    check(CapturePolicy.normalize("x".repeat(30_000))!!.length == 20_000)
    check(CapturePolicy.nameFor(line) == "LINE")
    println("PASS: 8 CapturePolicy checks")
}
