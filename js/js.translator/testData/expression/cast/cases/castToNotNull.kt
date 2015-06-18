package foo

import java.lang.*

trait A

class AImpl: A

fun test(x: Any?): A = x as A

fun box(): String {
    var a: A = AImpl()
    assertEquals(a, test(a), "a = AImpl()")
    a = object : A {}
    assertEquals(a, test(a), "a = object : A{}")
    failsClassCast("test(null)") { test(null) }
    failsClassCast("test(object{})") { test(object{}) }

    return "OK"
}