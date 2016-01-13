// !DIAGNOSTICS: -UNUSED_PARAMETER

class A {
    operator fun set(x: Int, y: String = "y", z: Double = 3.14) {
    }
}

fun test() {
    val a = A()
    a[0] = <!TYPE_MISMATCH!>""<!>
    a[0] = 2.72
}
