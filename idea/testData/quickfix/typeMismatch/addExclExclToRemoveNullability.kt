// "Add non-null asserted (!!) call" "true"
fun test() {
    val s: String? = null
    other(<caret>s)
}

fun other(s: String) {}