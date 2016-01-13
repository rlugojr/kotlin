// !DIAGNOSTICS: -UNUSED_PARAMETER

interface C<out T>
interface MC<T> : C<T> {
    fun addAll(x: C<T>): Boolean
    fun addAllMC(x: MC<out T>): Boolean
}

interface Open
class Derived : Open

fun <T> mc(): MC<T> = null!!
fun <T> c(): C<T> = null!!

fun foo(x: MC<out Open>) {
    x.addAll(<!TYPE_MISMATCH_DUE_TO_TYPE_PROJECTIONS(C<kotlin.Nothing>; MC<out Open>; MC<out Open>)!>x<!>)
    x.addAllMC(<!TYPE_MISMATCH_DUE_TO_TYPE_PROJECTIONS(MC<kotlin.Nothing>; MC<out Open>; MC<out Open>)!>x<!>)

    x.addAll(<!TYPE_MISMATCH_DUE_TO_TYPE_PROJECTIONS(C<kotlin.Nothing>; MC<Open>; MC<out Open>)!>mc<Open>()<!>)
    x.addAllMC(<!TYPE_MISMATCH_DUE_TO_TYPE_PROJECTIONS(MC<kotlin.Nothing>; MC<Open>; MC<out Open>)!>mc<Open>()<!>)

    x.addAll(<!TYPE_MISMATCH_DUE_TO_TYPE_PROJECTIONS(C<kotlin.Nothing>; MC<Derived>; MC<out Open>)!>mc<Derived>()<!>)
    x.addAllMC(<!TYPE_MISMATCH_DUE_TO_TYPE_PROJECTIONS(MC<kotlin.Nothing>; MC<Derived>; MC<out Open>)!>mc<Derived>()<!>)

    x.addAll(c())
    x.addAll(c<Nothing>())
}
