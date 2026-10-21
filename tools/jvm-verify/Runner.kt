import java.lang.reflect.Modifier

fun main(args: Array<String>) {
    var passed = 0
    val failures = mutableListOf<String>()
    for (className in args) {
        val clazz = Class.forName(className)
        val before = clazz.methods.filter { it.isAnnotationPresent(org.junit.Before::class.java) }
        val after = clazz.methods.filter { it.isAnnotationPresent(org.junit.After::class.java) }
        val tests = clazz.methods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.sortedBy { it.name }
        println("## $className (${tests.size} tests)")
        for (test in tests) {
            val instance = clazz.getDeclaredConstructor().newInstance()
            try {
                before.forEach { it.invoke(instance) }
                test.invoke(instance)
                passed++
                println("PASS ${test.name}")
            } catch (error: java.lang.reflect.InvocationTargetException) {
                val cause = error.targetException
                failures += "$className.${test.name}: ${cause::class.java.simpleName}: ${cause.message}"
                println("FAIL ${test.name} -> ${cause::class.java.simpleName}: ${cause.message}")
            } catch (error: Throwable) {
                failures += "$className.${test.name}: ${error.message}"
                println("FAIL ${test.name} -> $error")
            } finally {
                runCatching { after.forEach { it.invoke(instance) } }
            }
        }
    }
    println()
    println("RESULT passed=$passed failed=${failures.size}")
    failures.forEach { println("  - $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}
