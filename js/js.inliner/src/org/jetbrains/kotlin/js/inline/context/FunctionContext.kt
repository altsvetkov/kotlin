/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.inline.context

import com.google.dart.compiler.backend.js.ast.*
import com.google.dart.compiler.backend.js.ast.metadata.staticRef
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.js.inline.FunctionReader
import org.jetbrains.kotlin.js.inline.util.*
import java.util.IdentityHashMap

abstract class FunctionContext(
        private val function: JsFunction,
        private val inliningContext: InliningContext,
        private val functionReader: FunctionReader
) {
    /**
     * Caches function with captured arguments applied.
     *
     * @see getFunctionWithClosure
     */
    private val functionsWithClosure = IdentityHashMap<JsInvocation, JsFunction?>()

    protected abstract fun lookUpStaticFunction(functionName: JsName?): JsFunction?

    public fun getFunctionDefinition(call: JsInvocation): JsFunction {
        return getFunctionDefinitionImpl(call)!!
    }

    public fun hasFunctionDefinition(call: JsInvocation): Boolean {
        return getFunctionDefinitionImpl(call) != null
    }

    public fun getScope(): JsScope {
        return function.getScope()
    }

    public fun declareFunctionConstructorCalls(arguments: List<JsExpression>) {
        val calls = ContainerUtil.findAll<JsExpression, JsInvocation>(arguments, javaClass<JsInvocation>())

        for (call in calls) {
            val callName = getSimpleName(call)
            if (callName == null) continue

            val staticRef = callName.staticRef
            if (staticRef !is JsFunction) continue

            val functionCalled = staticRef
            if (isFunctionCreator(functionCalled)) {
                declareFunctionConstructorCall(call)
            }
        }
    }

    public fun declareFunctionConstructorCall(call: JsInvocation) {
        functionsWithClosure.put(call, null)
    }

    /**
     * Gets function definition by invocation.
     *
     * Notes:
     *      1. Qualifier -- [()/.call()] part of invocation.
     *      2. Local functions are compiled like function literals,
     *      but called not directly, but through variable.
     *
     *      For example, local `fun f(a, b) = a + b; f(1, 2)` becomes `var f = _.foo.f$; f(1, 2)`
     *
     * Invocation properties:
     * 1. Ends with either [()/.call()].
     *
     * 2. Qualifier can be JsNameRef with static ref to JsFunction
     *    in case of function literal without closure.
     *
     *    For example, qualifier == _.foo.lambda$
     *
     * 3. Qualifier can be JsInvocation with static ref to JsFunction
     *    in case of function literal with closure. In this case
     *    qualifier arguments are captured in closure.
     *
     *    For example, qualifier == _.foo.lambda(captured_1)
     *
     * 4. Qualifier can be JsNameRef with static ref to case [2]
     *    in case of local function without closure.
     *
     * 5. Qualifier can be JsNameRef with ref to case [3]
     *    in case of local function with closure.
     */
    private fun getFunctionDefinitionImpl(call: JsInvocation): JsFunction? {
        if (functionReader.isCallToFunctionFromLibrary(call)) return functionReader.getLibraryFunctionDefinition(call)

        /** remove ending `()` */
        var callQualifier = call.getQualifier()

        /** remove ending `.call()` */
        if (isCallInvocation(call)) {
            callQualifier = (callQualifier as JsNameRef).getQualifier()!!
        }

        /** in case 4, 5 get ref (reduce 4, 5 to 2, 3 accordingly) */
        if (callQualifier is JsNameRef) {
            val staticRef = (callQualifier as JsNameRef).getName()?.staticRef

            callQualifier = when (staticRef) {
                is JsNameRef -> staticRef
                is JsInvocation -> staticRef
                is JsFunction, null -> callQualifier
                else -> throw AssertionError("Unexpected static reference type ${staticRef.javaClass}")
            }
        }

        /** process cases 2, 3 */
        val qualifier = callQualifier
        return when (qualifier) {
            is JsInvocation -> getFunctionWithClosure(qualifier)
            is JsNameRef -> lookUpStaticFunction(qualifier.getName())
            else -> null
        }
    }

    /**
     * Gets function body with captured args applied,
     * and stores in cache.
     *
     * Function literals and local functions with closure
     * are translated as function, that returns function.
     *
     * For example,
     *      val a = 1
     *      val f = { a * 2 }
     * `f` becomes
     *      f: function (a) {
     *          return function () { return a * 2 }
     *      }
     *
     * @returns inner function with captured parameters,
     *          replaced by outer arguments
     *
     *          For invocation `f(10)()` returns
     *          `function () { return 10 * 2 }`
     */
    private fun getFunctionWithClosure(call: JsInvocation): JsFunction {
        val constructed = functionsWithClosure.get(call)

        if (constructed is JsFunction) return constructed

        val name = getSimpleName(call)!!
        val closureCreator = lookUpStaticFunction(name)!!
        val innerFunction = closureCreator.getInnerFunction()!!

        val withCapturedArgs = applyCapturedArgs(call, innerFunction, closureCreator)
        functionsWithClosure.put(call, withCapturedArgs)

        return withCapturedArgs
    }

    private fun applyCapturedArgs(call: JsInvocation, inner: JsFunction, outer: JsFunction): JsFunction {
        val innerClone = inner.deepCopy()

        val namingContext = inliningContext.newNamingContext()
        val arguments = call.getArguments()
        val parameters = outer.getParameters()
        aliasArgumentsIfNeeded(namingContext, arguments, parameters)
        namingContext.applyRenameTo(innerClone)

        return innerClone
    }
}
