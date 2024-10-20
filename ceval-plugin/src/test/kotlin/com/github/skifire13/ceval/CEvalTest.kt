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
  fun `if`() = assertNoEval(
    """
fun main() {
    assert(evalAddIf(1, 2) == 3)
    assert(evalAddIf(-1, 2) == 2)
}

fun evalAddIf(a: Int, b: Int): Int {
    if (a < 0) return b
    val sum: Int
    sum = a + b
    return sum
}
"""
  )

  @Test
  fun `loop`() = assertNoEval(
    """
fun main() {
    assert(evalAddLoop4(1, 2) == 3)
}

fun evalAddLoop4(a: Int, b: Int): Int {
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
  assertFalse(hasEvalCalls)
}

fun assertHasEval(@Language("kotlin") source: String) {
  val hasEvalCalls = compile(source)
  assert(hasEvalCalls)
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
