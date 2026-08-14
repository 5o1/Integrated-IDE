package me.funclogic.integratedscript.expr;

final class ExpressionCompileError extends RuntimeException {
    private final int position;

    ExpressionCompileError(int position, String message) {
        super(message);
        this.position = position;
    }

    int position() {
        return position;
    }
}
