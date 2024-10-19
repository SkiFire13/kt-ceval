@file:OptIn(ExperimentalCompilerApi::class)

package com.github.skifire13.ceval

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import kotlin.test.assertEquals
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Test

class IrPluginTest {
  @Test
  fun `IR plugin success`() {
    val result = compile(
      sourceFile = SourceFile.kotlin(
        "main.kt", """
fun main() {
    // evalAdd(1, 2) must be evaluated as 3
    println(evalAdd(1, 2))
}

fun evalAdd(a: Int, b: Int): Int {
    return a + b
}
"""
      )
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
  }
}

fun compile(
  sourceFiles: List<SourceFile>,
  plugin: CompilerPluginRegistrar = CEvalCompilerRegistrar(),
): JvmCompilationResult {
  return KotlinCompilation().apply {
    sources = sourceFiles
    compilerPluginRegistrars = listOf(plugin)
    inheritClassPath = true
  }.compile()
}

fun compile(
  sourceFile: SourceFile,
  plugin: CompilerPluginRegistrar = CEvalCompilerRegistrar(),
): JvmCompilationResult {
  return compile(listOf(sourceFile), plugin)
}
