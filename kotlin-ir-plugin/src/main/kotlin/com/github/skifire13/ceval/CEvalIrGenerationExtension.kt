package com.github.skifire13.ceval

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.referenceFunction
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.ir.visitors.acceptVoid

class CEvalIrGenerationExtension : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    println(moduleFragment.dump())
  }
}
