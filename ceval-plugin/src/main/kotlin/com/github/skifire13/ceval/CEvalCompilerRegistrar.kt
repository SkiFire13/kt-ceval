@file:OptIn(ExperimentalCompilerApi::class)

package com.github.skifire13.ceval

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@AutoService(CompilerPluginRegistrar::class)
class CEvalCompilerRegistrar: CompilerPluginRegistrar() {
  override val supportsK2 get() = false

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    IrGenerationExtension.registerExtension(CEvalIrGenerationExtension())
  }
}
