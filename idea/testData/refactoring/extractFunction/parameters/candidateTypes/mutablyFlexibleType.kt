// WITH_RUNTIME
// PARAM_DESCRIPTOR: val data: (kotlin.collections.MutableList<(kotlin.String..kotlin.String?)>..kotlin.collections.List<(kotlin.String..kotlin.String?)>) defined in test
// PARAM_TYPES: kotlin.collections.List<(kotlin.String..kotlin.String?)>, kotlin.collections.MutableList<(kotlin.String..kotlin.String?)>, kotlin.collections.MutableCollection<(kotlin.String..kotlin.String?)>, kotlin.collections.Collection<(kotlin.String..kotlin.String?)>
fun test(): Boolean {
    val j: J? = null
    val data = j?.getData() ?: return false
    return <selection>data.contains("foo")</selection>
}