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

package org.jetbrains.kotlin.resolve.diagnostics

import com.google.gwt.dev.js.AbortParsingException
import com.google.gwt.dev.js.rhino.*
import com.google.gwt.dev.js.rhino.Utils.*
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory2
import org.jetbrains.kotlin.diagnostics.DiagnosticSink
import org.jetbrains.kotlin.diagnostics.ParametrizedDiagnostic
import org.jetbrains.kotlin.js.patterns.DescriptorPredicate
import org.jetbrains.kotlin.js.patterns.PatternBuilder
import org.jetbrains.kotlin.js.resolve.diagnostics.ErrorsJs
import org.jetbrains.kotlin.psi.JetCallExpression
import org.jetbrains.kotlin.psi.JetExpression
import org.jetbrains.kotlin.psi.JetLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.JetStringTemplateExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.calls.checkers.CallChecker
import org.jetbrains.kotlin.resolve.calls.context.BasicCallResolutionContext
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.types.JetType

import com.intellij.openapi.util.TextRange
import java.io.StringReader

import kotlin.platform.platformStatic

public class JsCallChecker : CallChecker {

    class object {
        public val JS_PATTERN: DescriptorPredicate = PatternBuilder.pattern("kotlin.js.js(String)")

        platformStatic
        public fun <F : CallableDescriptor?> ResolvedCall<F>.matchesJsCode(): Boolean {
            val descriptor = getResultingDescriptor()
            return descriptor is SimpleFunctionDescriptor && JS_PATTERN.apply(descriptor)
        }
    }

    override fun <F : CallableDescriptor?> check(resolvedCall: ResolvedCall<F>, context: BasicCallResolutionContext) {
        if (context.isAnnotationContext || !resolvedCall.matchesJsCode()) return

        val expression = resolvedCall.getCall().getCallElement()
        if (expression !is JetCallExpression) return

        val arguments = expression.getValueArgumentList()?.getArguments()
        val argument = arguments?.firstOrNull()?.getArgumentExpression()!!

        if (!(checkArgumentIsStringLiteral(argument, context))) return

        checkSyntax(argument, context)
    }

    fun checkArgumentIsStringLiteral(
            argument: JetExpression,
            context: BasicCallResolutionContext
    ): Boolean {
        val stringType = KotlinBuiltIns.getInstance().getStringType()
        val evaluationResult = ConstantExpressionEvaluator.evaluate(argument, context.trace, stringType)

        if (evaluationResult == null) {
            context.trace.report(ErrorsJs.JSCODE_ARGUMENT_SHOULD_BE_LITERAL.on(argument))
        }

        return evaluationResult != null
    }

    fun checkSyntax(
            argument: JetExpression,
            context: BasicCallResolutionContext
    ): Boolean {
        val stringType = KotlinBuiltIns.getInstance().getStringType()
        val evaluationResult = ConstantExpressionEvaluator.evaluate(argument, context.trace, stringType)!!
        val code = evaluationResult.getValue() as String
        val reader = StringReader(code)

        val errorReporter = JsCodeErrorReporter(argument, code, context.trace)
        Context.enter().setErrorReporter(errorReporter)

        try {
            val ts = TokenStream(reader, "js", 0)
            val parser = Parser(IRFactory(ts), /* insideFunction = */ true)
            parser.parse(ts)
        } catch (e: AbortParsingException) {
            return false
        } finally {
            Context.exit()
        }

        return true
    }

}

private class JsCodeErrorReporter(
        private val nodeToReport: JetExpression,
        private val code: String,
        private val trace: BindingTrace
) : ErrorReporter {
    override fun warning(message: String, startPosition: CodePosition, endPosition: CodePosition) {
        report(ErrorsJs.JSCODE_WARNING, message, startPosition, endPosition)
    }

    override fun error(message: String, startPosition: CodePosition, endPosition: CodePosition) {
        report(ErrorsJs.JSCODE_ERROR, message, startPosition, endPosition)
        throw AbortParsingException()
    }

    private fun report(
            diagnosticFactory: DiagnosticFactory2<JetExpression, String, List<TextRange>>,
            message: String,
            startPosition: CodePosition,
            endPosition: CodePosition
    ) {
        val errorMessage: String
        val textRange: TextRange

        if (nodeToReport.isConstantStringLiteral) {
            errorMessage = message
            textRange = TextRange(startPosition.absoluteOffset, endPosition.absoluteOffset)
        } else {
            val underlined = code.underline(code.offsetOf(startPosition), code.offsetOf(endPosition))
            errorMessage = "%s%nEvaluated code:%n%s".format(message, underlined)
            textRange = nodeToReport.getTextRange()
        }

        val parametrizedDiagnostic = diagnosticFactory.on(nodeToReport, errorMessage, listOf(textRange))
        trace.report(parametrizedDiagnostic)
    }

    private val CodePosition.absoluteOffset: Int
        get() {
            val quotesLength = nodeToReport.getFirstChild().getTextLength()
            return nodeToReport.getTextOffset() + quotesLength + code.offsetOf(this)
        }
}

/**
 * Calculates an offset from the start of a text for a position,
 * defined by line and offset in that line.
 */
private fun String.offsetOf(position: CodePosition): Int {
    var i = 0
    var lineCount = 0
    var offsetInLine = 0

    while (i < length()) {
        val c = charAt(i)

        if (lineCount == position.line && offsetInLine == position.offset) {
            return i
        }

        if (isEndOfLine(c.toInt())) {
            offsetInLine = 0
            lineCount++
            assert(lineCount <= position.line)
        }

        i++
        offsetInLine++
    }

    return length()
}

private val JetExpression.isConstantStringLiteral: Boolean
    get() {
        return this is JetStringTemplateExpression &&
               getEntries().size() == 1 &&
               getEntries().first() is JetLiteralStringTemplateEntry
    }

/**
 * Underlines string in given rage.
 *
 * For example:
 * var  = 10;
 *    ^~~^
 */
private fun String.underline(from: Int, to: Int): String {
    val lines = StringBuilder()
    var marks = StringBuilder()
    var lineWasMarked = false

    for (i in indices) {
        val c = charAt(i)
        val mark: Char

        mark = when (i) {
            from, to -> '^'
            in from..to -> '~'
            else -> ' '
        }

        lines.append(c)
        marks.append(mark)
        lineWasMarked = lineWasMarked || mark != ' '

        if (isEndOfLine(c.toInt()) && lineWasMarked) {
            lines.appendln(marks.toString())
            marks = StringBuilder()
            lineWasMarked = false
        }
    }

    if (lineWasMarked) {
        lines.appendln()
        lines.append(marks.toString())
    }

    return lines.toString()
}
