@file:OptIn(ExperimentalCompilerApi::class)

package com.github.skifire13.ceval

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.visitors.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IrPluginTest {
  @Test
  fun `example`() = assertNoEval(
    """
    fun main() {
        assert(evalAdd(1, 2) == 3)
    }
    
    fun evalAdd(a: Int, b: Int): Int {
        val sum = a + b
        return sum
    }
    """
  )

  @Test
  fun `operations`() = assertNoEval(
    """
    fun main() {
        assert(evalArithmetic() == 6)
        assert(evalBitwise() == 19)
        assert(evalComparisons(1, 2, 2))
        assert(evalBooleans(true, false))
    }

    fun evalArithmetic() = (1 + 2) * (72 % 30) / 6
    fun evalBitwise() = (6 and 3) or (1 xor 17) or (0.inv() - 1).inv()
    fun evalComparisons(a: Int, b: Int, c: Int) = a != c && (b > a) && (b >= c) && !(a >= c) && (a < c) && (b <= c)
    fun evalBooleans(t: Boolean, f: Boolean) = (t && f) || !f
    """
  )

  @Test
  fun `if`() = assertNoEval(
    """
    fun main() {
        assert(evalAddIf(1, 2) == 3)
        assert(evalAddIf(-1, 2) == 2)
    }
    
    fun evalAddIf(a: Int, b: Int): Int {
        val sum: Int
        if (a < 0)
            return b
        else
            sum = a + b
        return sum
    }
    """
  )

  @Test
  fun `loopSimple`() = assertNoEval(
    """
    fun main() {
        assert(evalAddLoop(1, 2) == 3)
    }
    
    fun evalAddLoop(a: Int, b: Int): Int {
        var c = a
        var d: Int
        d = b
        while (c > 0) {
            c -= 1
            d++
        }
        return d
    }
    """
  )

  @Test
  fun `loop break continue`() = assertNoEval(
    """
    fun main() {
        assert(evalLoopBreakContinue(5, 2) == 23)
    }

    fun evalLoopBreakContinue(a: Int, b: Int): Int {
        var c = a
        var d = b
        while (c > 0) {
            c -= 1
            if (c == 4) continue
            if (d == 3) break
            d++
        }
        return 10 * c + d
    }
    """
  )

  @Test
  fun `loop nested`() = assertNoEval(
    """
    fun main() {
        assert(evalNestedLoops(4, 2) == 10)
    }

    fun evalNestedLoops(a: Int, b: Int): Int {
        var c = a
        var d = b
        outer@while (true) {
            while (c > 0) {
                d++
                if (d == 3) continue
                c--
                if (c == 2) break@outer
            }
            return 0
        }
        return c * d
    }
    """
  )

  @Test
  fun `string`() = assertNoEval(
    """
    fun main() {
        assert(evalConcat("foo", "bar") == "foobar")
    }
    
    fun evalConcat(a: String, b: String): String {
        return a + b
    }
    """
  )

  @Test
  fun `println`() = assertHasEval(
    """
    fun main() {
        assert(evalAdd(1, 2) == 3)
    }
    
    fun evalAdd(a: Int, b: Int): Int {
        println("Adding")
        return a + b
    }
    """
  )

  @Test
  fun `blockEvalToExpression`() = assertNoEval(
    """
    fun main() {
        assert(evalOne(true) == "OK")
        assert(evalOne(false) == "NOT OK")

        assert(evalTwo(true) == "OK")
        assert(evalTwo(false) == "NOT OK")
    }

    fun evalOne(flag: Boolean): String {
        return if (flag) { "OK" } else { "NOT OK" }
    }

    fun evalTwo(flag: Boolean): String =
        if (flag) { "OK" } else { "NOT OK" }
    """
  )

  @Test
  fun `defaultArgument`() = assertNoEval(
    """
    fun main() {
        assert(evalOne() == "OK")
        assert(evalOne(true) == "OK")
        assert(evalOne(false) == "NOT OK")

        assert(evalTwo() == "NOT OK")
        assert(evalTwo(true) == "OK")
        assert(evalTwo(false) == "NOT OK")
    }

    fun evalOne(flag: Boolean = true): String {
        return if (flag) { "OK" } else { "NOT OK" }
    }

    fun evalTwo(flag: Boolean = false): String {
        return if (flag) { "OK" } else { "NOT OK" }
    }
    """
  )

  @Test
  fun `nestedCall`() = assertNoEval(
    """
    fun main() {
        assert(evalFactorial(0) == 1)
        assert(evalFactorial(1) == 1)
        assert(evalFactorial(4) == 24)
    }
    
    fun evalFactorial(n: Int): Int = when (n) {
        0, 1 -> 1
        else -> evalFactorial(n - 1) * n
    }
    """
  )

  @Test
  fun `infinite`() {
    val result = KotlinCompilation().apply {
      sources = listOf(SourceFile.kotlin("main.kt", """
    fun main() {
        evalInfinite()
    }
    
    fun evalInfinite() {
        while(true) {}
    }
    """))
      compilerPluginRegistrars = listOf(CEvalCompilerRegistrar())
      inheritClassPath = true
    }.compile()
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
  }
}

fun assertNoEval(@Language("kotlin") source: String) {
  val hasEvalCalls = compile(source)
  assertEquals(hasEvalCalls, emptyList(), "Code should not have eval calls left")
}

fun assertHasEval(@Language("kotlin") source: String) {
  val hasEvalCalls = compile(source)
  assertNotEquals(hasEvalCalls, emptyList(), "Code should have eval calls left")
}

fun compile(@Language("kotlin") source: String): List<String> {
  val findEvalCalls = FindEvalCallsRegistrar()
  val result = KotlinCompilation().apply {
    sources = listOf(SourceFile.kotlin("main.kt", source))
    compilerPluginRegistrars = listOf(CEvalCompilerRegistrar(), findEvalCalls)
    inheritClassPath = true
  }.compile()
  assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
  result.classLoader.loadClass("MainKt").getMethod("main").invoke(null)
  return findEvalCalls.evalCalls
}

class FindEvalCallsRegistrar : CompilerPluginRegistrar() {
  var evalCalls = mutableListOf<String>()
  override val supportsK2 = true

  val functionsVisitor = object : IrElementVisitorVoid {
    override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
    override fun visitFunction(declaration: IrFunction) {
      if (declaration.name.asString() == "main") {
        declaration.acceptVoid(evalCallVisitor)
      }
      visitElement(declaration)
    }
  }

  val evalCallVisitor = object : IrElementVisitorVoid {
    override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
    override fun visitCall(expression: IrCall) {
      val funcName = expression.symbol.owner.name.asString()
      if (funcName.startsWith("eval")) {
        evalCalls.add(funcName)
      }
      visitElement(expression)
    }
  }

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    IrGenerationExtension.registerExtension(object : IrGenerationExtension {
      override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.acceptVoid(functionsVisitor)
      }
    })
  }
}
