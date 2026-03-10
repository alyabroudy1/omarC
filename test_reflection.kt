import java.lang.reflect.Method

fun main() {
    try {
        val clazz = Class.forName("com.lagradost.cloudstream3.ui.search.SearchViewModel")
        println("Class found!")
        val methods = clazz.declaredMethods
        for (m in methods) {
            println("Method: ${m.name}, Params: ${m.parameterTypes.joinToString { it.simpleName }}")
        }
    } catch (e: Exception) {
        println("Error: ${e.message}")
    }
}
