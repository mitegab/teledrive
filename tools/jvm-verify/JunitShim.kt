// Minimal JUnit4-compatible annotations/assertions + reflective runner for the
// offline verification harness. Not part of the app.
package org.junit

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Before

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class After

object Assert {
    @JvmStatic fun fail(message: String?): Nothing = throw AssertionError(message ?: "fail")
    @JvmStatic fun fail(message: String?, cause: Throwable?): Nothing = throw AssertionError(message ?: "fail", cause)
    @JvmStatic fun assertTrue(condition: Boolean) { if (!condition) fail("expected true") }
    @JvmStatic fun assertTrue(message: String?, condition: Boolean) { if (!condition) fail(message) }
    @JvmStatic fun assertFalse(condition: Boolean) { if (condition) fail("expected false") }
    @JvmStatic fun assertFalse(message: String?, condition: Boolean) { if (condition) fail(message) }
    @JvmStatic fun assertNull(value: Any?) { if (value != null) fail("expected null but was <$value>") }
    @JvmStatic fun assertNull(message: String?, value: Any?) { if (value != null) fail(message) }
    @JvmStatic fun assertNotNull(value: Any?) { if (value == null) fail("expected not null") }
    @JvmStatic fun assertNotNull(message: String?, value: Any?) { if (value == null) fail(message) }
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) { assertEquals(null, expected, actual) }
    @JvmStatic fun assertEquals(message: String?, expected: Any?, actual: Any?) {
        if (expected != actual) fail((message?.plus(": ") ?: "") + "expected <$expected> but was <$actual>")
    }
    @JvmStatic fun assertEquals(expected: Long, actual: Long) { if (expected != actual) fail("expected <$expected> but was <$actual>") }
    @JvmStatic fun assertEquals(message: String?, expected: Long, actual: Long) { if (expected != actual) fail((message?.plus(": ") ?: "") + "expected <$expected> but was <$actual>") }
    @JvmStatic fun assertEquals(expected: Int, actual: Int) { if (expected != actual) fail("expected <$expected> but was <$actual>") }
    @JvmStatic fun assertEquals(expected: Double, actual: Double, delta: Double) { if (kotlin.math.abs(expected - actual) > delta) fail("expected <$expected> but was <$actual>") }
    @JvmStatic fun assertEquals(expected: Float, actual: Float, delta: Float) { if (kotlin.math.abs(expected - actual) > delta) fail("expected <$expected> but was <$actual>") }
    @JvmStatic fun assertNotEquals(expected: Any?, actual: Any?) { if (expected == actual) fail("expected values to differ but both were <$expected>") }
    @JvmStatic fun assertSame(expected: Any?, actual: Any?) { if (expected !== actual) fail("expected same instance") }
}
