@file:OptIn(ExperimentalCompilerApi::class)

package com.github.skifire13.ceval

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@AutoService(CommandLineProcessor::class)
class CEvalCommandLineProcessor : CommandLineProcessor {
  override val pluginId: String = BuildConfig.KOTLIN_PLUGIN_ID

  override val pluginOptions: Collection<CliOption> = emptyList()

  override fun processOption(
    option: AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration
  ) {
    throw IllegalArgumentException("Unexpected config option ${option.optionName}")
  }
}
