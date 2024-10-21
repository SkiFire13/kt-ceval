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
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `loop`() = assertNoEval(
    """
    fun main() {
        assert(evalAddLoop(1, 2) == 3)
        assert(evalLoopBreakContinue(5, 2) == 23)
        assert(evalNestedLoops(4, 2) == 10)
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
}

fun assertNoEval(@Language("kotlin") source: String) {
  val hasEvalCalls = compile(source)
  assertFalse(hasEvalCalls, "Code should not have eval calls left")
}

fun assertHasEval(@Language("kotlin") source: String) {
  val hasEvalCalls = compile(source)
  assertTrue(hasEvalCalls, "Code should have eval calls left")
}

fun compile(@Language("kotlin") source: String): Boolean {
  val findEvalCalls = FindEvalCallsRegistrar()
  val result = KotlinCompilation().apply {
    sources = listOf(SourceFile.kotlin("main.kt", source))
    compilerPluginRegistrars = listOf(CEvalCompilerRegistrar(), findEvalCalls)
    inheritClassPath = true
  }.compile()
  assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
  result.classLoader.loadClass("MainKt").getMethod("main").invoke(null)
  return findEvalCalls.hasEvalCalls
}

class FindEvalCallsRegistrar : CompilerPluginRegistrar() {
  var hasEvalCalls = false
  override val supportsK2 = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    IrGenerationExtension.registerExtension(object : IrGenerationExtension {
      override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.transformChildrenVoid(object : IrElementTransformerVoid() {
          override fun visitCall(expression: IrCall): IrExpression {
            hasEvalCalls = hasEvalCalls || expression.symbol.owner.name.asString().startsWith("eval")
            return super.visitCall(expression)
          }
        })
      }
    })
  }
}
