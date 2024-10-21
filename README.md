# CEval

A Kotlin IR plugin to evaluate simple constant expressions and functions.

## Implementation details

The most important parts are in the [`CEvalIrGenerationExtension.kt`](./ceval-plugin/src/main/kotlin/com/github/skifire13/ceval/CEvalIrGenerationExtension.kt) and [`CEvalEvaluator.kt`](./ceval-plugin/src/main/kotlin/com/github/skifire13/ceval/CEvalEvaluator.kt) files.

The `CEvalIrGenerationExtension` class is responsible for looking at all the call expressions using the `transformChildrenVoid` method, it then filters those that start with `eval` and tries to compute their value using the `FunEvalContext` (defined in `CEvalEvaluator.kt`). If this is successful it then replaces the call expression with a constant expression containing the result.

The `FunEvalContext` class instead is responsible for trying to evaluate expressions. The entrypoint is the `evalExpression` method, which evaluates an `IrExpression` by checking if its one of the supported implementors and in that case executes the corresponding code for it.

The values of local variables during the execution are managed by the `locals` map field in `FunEvalContext`, which maps `IrSymbol`s of local variables to their `Value`s. This is initially empty, both when `CEvalIrGenerationExtension` tries to evaluate a call expression, because that shouldn't depend on external state, and when the constant evaluated function calls another function (excluding built-in functions).

The control flow during constant evaluation is handled by having every evaluation attempt return an `EvalRes`. This can be:
- a value of a constant type, which represents an expression evaluating to that value;
- `Unit`, which is used as the value when successfully evaluating statements;
- `Break` and `Continue`, representing a diverging expression or block trying to respectively `break` or `continue` from a given `IrLoop` (which is also used to disambiguate these operations in nested loops);
- `Return`, representing a diverging expression or block trying to return from the current function;
- `Invalid`, representing either some kind of unsupported operation (for example a call to a runtime-only function like `println`) or an error, which makes the constant computation bail out, leaving the function to be computed at runtime.

Some care is also present for handling functions that may be running infinitely and hence block the compiler, for example due to infinite loops. Every instance of `FunEvalContext` is provided with a limit to the number of expression evaluations it can perform, and reaching that limit results in an `Invalid` result, which stops the constant evaluation. 
