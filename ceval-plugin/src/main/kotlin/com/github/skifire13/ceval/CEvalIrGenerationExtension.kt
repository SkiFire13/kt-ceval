package com.github.skifire13.ceval

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.toIrConst
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class CEvalIrGenerationExtension : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val intTy = pluginContext.irBuiltIns.intType
    val booleanTy = pluginContext.irBuiltIns.booleanType
    val stringTy = pluginContext.irBuiltIns.stringType

    val builtInOperators = BuiltInOperators(pluginContext.irBuiltIns)

    moduleFragment.transformChildrenVoid(object : IrElementTransformerVoid() {
      override fun visitCall(expression: IrCall): IrExpression {
        if (expression.symbol.owner.name.asString().startsWith("eval")) {
          val visitor = FunEvalVisitor(builtInOperators, StepsLimit(100_00))
          try {
            when(val value = expression.accept(visitor, Unit)) {
              is EvalRes.Value.Int -> return value.i.toIrConst(intTy)
              is EvalRes.Value.Boolean -> return value.b.toIrConst(booleanTy)
              is EvalRes.Value.String -> return value.s.toIrConst(stringTy)
              else -> {}
            }
          } catch (e: EvalException) {}
        }
        return super.visitCall(expression)
      }
    })
  }
}
