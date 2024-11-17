package com.github.skifire13.ceval

import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.getAllArgumentsWithIr
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.name.Name

sealed interface EvalRes {
    class Return(val v: Value) : EvalRes
    class Break(val loop: IrLoop) : EvalRes
    class Continue(val loop: IrLoop) : EvalRes
    sealed interface Value : EvalRes {
        class Int(val i: kotlin.Int) : Value
        class Boolean(val b: kotlin.Boolean) : Value
        class String(val s: kotlin.String) : Value
        data object Unit : Value
    }
}

abstract class EvalException: Exception()
class TooManyStepsException: EvalException()
class UnsupportedIrException: EvalException()
class InvalidStateException: EvalException()
class ExpectedIntException: EvalException()
class ExpectedBooleanException: EvalException()
class ExpectedStringException: EvalException()
class UnsupportedFunctionException: EvalException()

typealias Value = EvalRes.Value
typealias VInt = EvalRes.Value.Int
typealias VBoolean = EvalRes.Value.Boolean
typealias VString = EvalRes.Value.String

inline fun EvalRes.valueOr(f: (EvalRes) -> Value) = if (this is Value) this else f(this)
fun Value.expectInt(): Int = if (this is VInt) this.i else throw ExpectedIntException()
fun Value.expectBoolean(): Boolean = if (this is VBoolean) this.b else throw ExpectedBooleanException()
fun Value.expectString(): String = if (this is VString) this.s else throw ExpectedStringException()

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
    fun checkAndStep() {
        if (limit == 0) throw TooManyStepsException()
        limit--
    }
}

class FunEvalVisitor(private val ops: BuiltInOperators, private val stepsLimit: StepsLimit) : IrElementVisitor<EvalRes, Unit> {
    private val locals: MutableMap<IrSymbol, Value> = mutableMapOf()

    override fun visitElement(element: IrElement, data: Unit): EvalRes = throw UnsupportedIrException()

    override fun visitCall(expression: IrCall, data: Unit): EvalRes {
        stepsLimit.checkAndStep()

        // If the body is unknown the function may be a builtin
        val body = expression.symbol.owner.body ?: return evalBuiltInCall(expression)

        // Create a new context for evaluating the new function
        val newContext = FunEvalVisitor(ops, stepsLimit)

        // Evaluate the function parameters
        for ((paramDef, paramExpr) in expression.getAllArgumentsWithIr()) {
            val expr = paramExpr ?: paramDef.defaultValue?.expression ?: throw UnsupportedIrException()
            newContext.locals[paramDef.symbol] = expr.accept(this, Unit).valueOr { return it }
        }

        return when (val res = body.accept(newContext, Unit)) {
            // The function returned via an explicit return
            is EvalRes.Return -> res.v
            // The function implicitly returned unit (this doesn't really make sense for const-able functions though)
            is EvalRes.Value -> res
            // Other ways to exit the function should be invalid
            else -> throw InvalidStateException()
        }
    }

    override fun visitConst(expression: IrConst<*>, data: Unit): EvalRes {
        return when (expression.kind) {
            is IrConstKind.Int -> VInt(expression.value as? Int ?: throw ExpectedIntException())
            is IrConstKind.Boolean -> VBoolean(expression.value as? Boolean ?: throw ExpectedBooleanException())
            is IrConstKind.String -> VString(expression.value as? String ?: throw ExpectedStringException())
            else -> throw UnsupportedIrException()
        }
    }

    override fun visitGetValue(expression: IrGetValue, data: Unit): EvalRes =
        locals[expression.symbol] ?: throw InvalidStateException()

    override fun visitSetValue(expression: IrSetValue, data: Unit): EvalRes {
        locals[expression.symbol] = expression.value.accept(this, Unit).valueOr { return it }
        return EvalRes.Value.Unit
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall, data: Unit): EvalRes =
        if (expression.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT)
            expression.argument.accept(this, Unit)
        else
            throw UnsupportedIrException()

    override fun visitBlock(expression: IrBlock, data: Unit): EvalRes =
        evalStatements(expression.statements)

    override fun visitReturn(expression: IrReturn, data: Unit): EvalRes {
        return EvalRes.Return(expression.value.accept(this, Unit).valueOr { return it })
    }

    override fun visitWhen(expression: IrWhen, data: Unit): EvalRes {
        for (branch in expression.branches)
            if (branch.condition.accept(this, Unit).valueOr { return it }.expectBoolean())
                return branch.result.accept(this, Unit)
        return EvalRes.Value.Unit
    }

    override fun visitWhileLoop(loop: IrWhileLoop, data: Unit): EvalRes =
        if (loop.condition.accept(this, Unit).valueOr { return it }.expectBoolean()) evalLoopCommon(loop)
        else EvalRes.Value.Unit

    override fun visitDoWhileLoop(loop: IrDoWhileLoop, data: Unit): EvalRes =
        evalLoopCommon(loop)

    private fun evalLoopCommon(loop: IrLoop): EvalRes {
        do {
            stepsLimit.checkAndStep()

            // Evaluate the body
            val bodyRes = loop.body?.accept(this, Unit) ?: EvalRes.Value.Unit
            when {
                // Apply break and continue if they're for this loop
                bodyRes is EvalRes.Break && bodyRes.loop == loop -> break
                bodyRes is EvalRes.Continue && bodyRes.loop == loop -> {}
                // Values are ignored
                bodyRes is Value -> {}
                // Otherwise propagate
                else -> return bodyRes
            }

            val cond = loop.condition.accept(this, Unit).valueOr { return it }.expectBoolean()
        } while(cond)

        // Loops always evaluate to unit
        return EvalRes.Value.Unit
    }

    override fun visitBreak(jump: IrBreak, data: Unit): EvalRes =
        EvalRes.Break(jump.loop)

    override fun visitContinue(jump: IrContinue, data: Unit): EvalRes =
        EvalRes.Continue(jump.loop)

    override fun visitBlockBody(body: IrBlockBody, data: Unit): EvalRes =
        evalStatements(body.statements)

    override fun visitExpressionBody(body: IrExpressionBody, data: Unit): EvalRes =
        body.expression.accept(this, Unit)

    override fun visitVariable(declaration: IrVariable, data: Unit): EvalRes {
        val init = declaration.initializer
        if (init != null) {
            locals[declaration.symbol] = init.accept(this, Unit).valueOr { return it }
        }
        return EvalRes.Value.Unit
    }

    private fun evalBuiltInCall(call: IrCall): EvalRes {
        val args = call.getAllArgumentsWithIr().map { (paramDef, paramExpr) ->
            val expression = paramExpr ?: paramDef.defaultValue?.expression ?: throw InvalidStateException()
            expression.accept(this, Unit)
        }

        // Helpers for evaluating binary functions taking two `Int`s and returning either an `Int` or a `Boolean`
        fun evalIntBinary(comb: (Int, Int) -> EvalRes): EvalRes {
            if (args.size != 2) throw InvalidStateException()
            val left = args[0].valueOr { return it }.expectInt()
            val right = args[1].valueOr { return it }.expectInt()
            return comb(left, right)
        }
        fun evalIntBinaryInt(comb: (Int, Int) -> Int) = evalIntBinary { l, r -> VInt(comb(l, r)) }
        fun evalIntBinaryBoolean(comb: (Int, Int) -> Boolean) = evalIntBinary { l, r -> VBoolean(comb(l, r)) }

        // Helpers for evaluating unary functions taking an `Int`
        fun evalIntUnary(f: (Int) -> Int): EvalRes {
            if (args.size != 1) throw InvalidStateException()
            val arg = args[0].valueOr { return it }.expectInt()
            return VInt(f(arg))
        }

        // Helpers for evaluating short-circuiting binary functions taking two `Boolean`s and returning another `Boolean`
        fun evalBooleanBinary(short: (Boolean) -> Boolean, comb: (Boolean, Boolean) -> Boolean): EvalRes {
            if (args.size != 2) throw InvalidStateException()
            val left = args[0].valueOr { return it }.expectBoolean()
            if (short(left)) return VBoolean(comb(left, true)) // Right doesn't matter
            val right = args[1].valueOr { return it }.expectBoolean()
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
                if (args.size != 1) throw InvalidStateException()
                val bool = args[0].valueOr { return it }.expectBoolean()
                VBoolean(!bool)
            }
            ops.stringPlus, ops.stringExtPlus -> {
                if (args.size != 2) throw InvalidStateException()
                val left = args[0].valueOr { return it }.expectString()
                val right = args[1].valueOr { return it }.expectString()
                VString(left + right)
            }
            // Other unknown functions are unsupported
            else -> throw UnsupportedFunctionException()
        }
    }

    private fun evalStatements(statements: List<IrStatement>): EvalRes {
        var res: EvalRes.Value = EvalRes.Value.Unit
        for (stmt in statements) {
            res = stmt.accept(this, Unit).valueOr { return it }
        }
        return res
    }
}
