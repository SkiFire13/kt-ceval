package com.github.skifire13.ceval

import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.getAllArgumentsWithIr
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.name.Name

sealed interface EvalRes {
  class Return(val v: Value) : EvalRes
  class Break(val loop: IrLoop) : EvalRes
  class Continue(val loop: IrLoop) : EvalRes
  data object Invalid : EvalRes
  sealed interface Value : EvalRes {
    class Int(val i: kotlin.Int) : Value
    class Boolean(val b: kotlin.Boolean) : Value
    class String(val s: kotlin.String) : Value
    data object Unit : Value
  }
}

typealias Value = EvalRes.Value
typealias VInt = EvalRes.Value.Int
typealias VBoolean = EvalRes.Value.Boolean
typealias VString = EvalRes.Value.String

inline fun EvalRes.valueOr(f: (EvalRes) -> Value): Value = if (this is Value) this else f(this)
inline fun Value.intOr(f: (Value) -> Int): Int = if (this is VInt) this.i else f(this)
inline fun Value.booleanOr(f: (Value) -> Boolean): Boolean = if (this is VBoolean) this.b else f(this)
inline fun Value.stringOr(f: (Value) -> String): String = if (this is VString) this.s else f(this)

class BuiltInOperators(irBuiltIns: IrBuiltIns) {
  val plus = irBuiltIns.intPlusSymbol
  val minus = irBuiltIns.getBinaryOperator(Name.identifier("minus"), irBuiltIns.intType, irBuiltIns.intType)
  val mul = irBuiltIns.intTimesSymbol
  val div = irBuiltIns.getBinaryOperator(Name.identifier("div"), irBuiltIns.intType, irBuiltIns.intType)
  val rem = irBuiltIns.getBinaryOperator(Name.identifier("rem"), irBuiltIns.intType, irBuiltIns.intType)
  val inc = irBuiltIns.getUnaryOperator(Name.identifier("inc"), irBuiltIns.intType)
  val dec = irBuiltIns.getUnaryOperator(Name.identifier("dec"), irBuiltIns.intType)
  val bitAnd = irBuiltIns.getBinaryOperator(Name.identifier("and"), irBuiltIns.intType, irBuiltIns.intType)
  val bitOr = irBuiltIns.getBinaryOperator(Name.identifier("or"), irBuiltIns.intType, irBuiltIns.intType)
  val bitNot = irBuiltIns.getUnaryOperator(Name.identifier("inv"), irBuiltIns.intType)
  val bitXor = irBuiltIns.intXorSymbol

  val gt = irBuiltIns.greaterFunByOperandType[irBuiltIns.intClass]
  val ge = irBuiltIns.greaterOrEqualFunByOperandType[irBuiltIns.intClass]
  val lt = irBuiltIns.lessFunByOperandType[irBuiltIns.intClass]
  val le = irBuiltIns.lessOrEqualFunByOperandType[irBuiltIns.intClass]
  val eq = irBuiltIns.eqeqSymbol

  val and = irBuiltIns.andandSymbol
  val or = irBuiltIns.ororSymbol
  val not = irBuiltIns.booleanNotSymbol

  val stringPlus = irBuiltIns.memberStringPlus
  val stringExtPlus = irBuiltIns.extensionStringPlus
}

class StepsLimit(private var limit: Int) {
  fun checkAndStep(): Boolean {
    if (limit == 0) return false
    limit--
    return true
  }
}

class FunEvalContext(private val ops: BuiltInOperators, private val stepsLimit: StepsLimit) {
  private val locals: MutableMap<IrSymbol, Value> = mutableMapOf()

  fun evalExpr(expr: IrExpression): EvalRes {
    if (!stepsLimit.checkAndStep()) return EvalRes.Invalid

    return when (expr) {
      is IrConst<*> -> when (expr.kind) {
        is IrConstKind.Int -> VInt(expr.value as? Int ?: return EvalRes.Invalid)
        is IrConstKind.Boolean -> VBoolean(expr.value as? Boolean ?: return EvalRes.Invalid)
        is IrConstKind.String -> VString(expr.value as? String ?: return EvalRes.Invalid)
        else -> return EvalRes.Invalid
      }

      is IrGetValue -> locals[expr.symbol] ?: return EvalRes.Invalid
      is IrSetValue -> {
        locals[expr.symbol] = evalExpr(expr.value).valueOr { return it }
        EvalRes.Value.Unit
      }

      is IrTypeOperatorCall ->
        if (expr.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) evalExpr(expr.argument)
        else EvalRes.Invalid

      is IrBlock -> evalStmts(expr.statements)

      is IrCall -> evalCall(expr)
      is IrReturn -> EvalRes.Return(evalExpr(expr.value).valueOr { return it })

      is IrWhen -> {
        for (branch in expr.branches)
          if (evalExpr(branch.condition).valueOr { return it }.booleanOr { return EvalRes.Invalid })
            return evalExpr(branch.result)
        EvalRes.Value.Unit
      }

      is IrLoop -> evalLoop(expr)
      is IrBreak -> return EvalRes.Break(expr.loop)
      is IrContinue -> return EvalRes.Continue(expr.loop)

      else -> EvalRes.Invalid
    }
  }

  private fun evalCall(call: IrCall): EvalRes {
    // If the body is unknown the function may be a builtin
    val body = call.symbol.owner.body ?: return evalBuiltinCall(call)

    // Create a new context for evaluating the new function
    val newContext = FunEvalContext(ops, stepsLimit)

    // Evaluate the function parameters
    for ((paramDef, paramExpr) in call.getAllArgumentsWithIr()) {
      val expr = paramExpr ?: paramDef.defaultValue?.expression ?: return EvalRes.Invalid
      newContext.locals[paramDef.symbol] = evalExpr(expr).valueOr { return it }
    }

    return when (val res = newContext.evalStmts(body.statements)) {
      // The function returned via an explicit return
      is EvalRes.Return -> res.v
      // The function implicitly returned unit (this doesn't really make sense for const-able functions though)
      is EvalRes.Value.Unit -> EvalRes.Value.Unit
      // Other ways to exit the function should be invalid
      else -> EvalRes.Invalid
    }
  }

  private fun evalBuiltinCall(call: IrCall): EvalRes {
    val args = call.getAllArgumentsWithIr().map { (paramDef, paramExpr) ->
      evalExpr(paramExpr ?: paramDef.defaultValue?.expression ?: return EvalRes.Invalid)
    }

    // Helpers for evaluating binary functions taking two `Int`s and returning either an `Int` or a `Boolean`
    fun evalIntBinary(comb: (Int, Int) -> EvalRes): EvalRes {
      if (args.size != 2) return EvalRes.Invalid
      val left = args[0].valueOr { return it }.intOr { return EvalRes.Invalid }
      val right = args[1].valueOr { return it }.intOr { return EvalRes.Invalid }
      return comb(left, right)
    }
    fun evalIntBinaryInt(comb: (Int, Int) -> Int) = evalIntBinary { l, r -> VInt(comb(l, r)) }
    fun evalIntBinaryBoolean(comb: (Int, Int) -> Boolean) = evalIntBinary { l, r -> VBoolean(comb(l, r)) }

    // Helpers for evaluating unary functions taking an `Int`
    fun evalIntUnary(f: (Int) -> Int): EvalRes {
      if (args.size != 1) return EvalRes.Invalid
      val arg = args[0].valueOr { return it }.intOr { return EvalRes.Invalid }
      return VInt(f(arg))
    }

    // Helpers for evaluating short-circuiting binary functions taking two `Boolean`s and returning another `Boolean`
    fun evalBooleanBinary(short: (Boolean) -> Boolean, comb: (Boolean, Boolean) -> Boolean): EvalRes {
      if (args.size != 2) return EvalRes.Invalid
      val left = args[0].valueOr { return it }.booleanOr { return EvalRes.Invalid }
      if (short(left)) return VBoolean(comb(left, true)) // Right doesn't matter
      val right = args[1].valueOr { return it }.booleanOr { return EvalRes.Invalid }
      return VBoolean(comb(left, right))
    }

    return when (call.symbol) {
      ops.plus -> evalIntBinaryInt { l, r -> l + r }
      ops.minus -> evalIntBinaryInt { l, r -> l - r }
      ops.mul -> evalIntBinaryInt { l, r -> l * r }
      ops.div -> evalIntBinaryInt { l, r -> l / r }
      ops.rem -> evalIntBinaryInt { l, r -> l % r }
      ops.inc -> evalIntUnary { it + 1 }
      ops.dec -> evalIntUnary { it - 1 }
      ops.bitAnd -> evalIntBinaryInt { l, r -> l and r }
      ops.bitOr -> evalIntBinaryInt { l, r -> l or r }
      ops.bitNot -> evalIntUnary { it.inv() }
      ops.bitXor -> evalIntBinaryInt { l, r -> l xor r }
      ops.gt -> evalIntBinaryBoolean { l, r -> l > r }
      ops.ge -> evalIntBinaryBoolean { l, r -> l >= r }
      ops.lt -> evalIntBinaryBoolean { l, r -> l < r }
      ops.le -> evalIntBinaryBoolean { l, r -> l <= r }
      ops.eq -> evalIntBinaryBoolean { l, r -> l == r }
      ops.and -> evalBooleanBinary({ !it }) { l, r -> l && r }
      ops.or -> evalBooleanBinary({ it }) { l, r -> l || r }
      ops.not -> {
        if (args.size != 1) return EvalRes.Invalid
        val bool = args[0].valueOr { return it }.booleanOr { return EvalRes.Invalid }
        VBoolean(!bool)
      }
      ops.stringPlus, ops.stringExtPlus -> {
        if (args.size != 2) return EvalRes.Invalid
        val left = args[0].valueOr { return it }.stringOr { return EvalRes.Invalid }
        val right = args[1].valueOr { return it }.stringOr { return EvalRes.Invalid }
        VString(left + right)
      }
      // Other unknown functions are unsupported
      else -> EvalRes.Invalid
    }
  }

  private fun evalLoop(loop: IrLoop): EvalRes {
    var cond = when (loop) {
      // While loop evaluate the condition initially
      is IrWhileLoop -> evalExpr(loop.condition).valueOr { return it }.booleanOr { return EvalRes.Invalid }
      // Do-while loops first evaluate the body, so cond is initially true
      is IrDoWhileLoop -> true
      // Other kind of loops are unexpected
      else -> return EvalRes.Invalid
    }

    while (cond) {
      // Evaluate the body
      val bodyRes = loop.body?.let { evalExpr(it) } ?: EvalRes.Value.Unit
      when {
        // Apply break and continue if they're for this loop
        bodyRes is EvalRes.Break && bodyRes.loop == loop -> break
        bodyRes is EvalRes.Continue && bodyRes.loop == loop -> {}
        // Unit represents a successful loop iteration
        bodyRes is EvalRes.Value.Unit -> {}
        // Other values should not be present
        bodyRes is Value -> return EvalRes.Invalid
        // Otherwise propagate
        else -> return bodyRes
      }

      // Evaluate the condition for the next iteration
      cond = evalExpr(loop.condition).valueOr { return it }.booleanOr { return EvalRes.Invalid }
    }

    // Loops always evaluate to unit
    return EvalRes.Value.Unit
  }

  private fun evalStmts(statements: List<IrStatement>): EvalRes {
    for (stmt in statements) {
      when (stmt) {
        is IrVariable -> stmt.initializer?.let { init -> locals[stmt.symbol] = evalExpr(init).valueOr { return it } }
        is IrExpression -> evalExpr(stmt).valueOr { return it }
        else -> return EvalRes.Invalid
      }
    }
    // List of statements always evaluate to Unit
    return EvalRes.Value.Unit
  }
}
