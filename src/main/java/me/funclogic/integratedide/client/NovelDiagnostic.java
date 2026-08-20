package me.funclogic.integratedide.client;

import me.funclogic.integratedide.expr.ExpressionCompiler;

/** A durable, user-facing message for the Novel editor's diagnostic area. */
record NovelDiagnostic(Severity severity, String text) {
    enum Severity {
        INFO,
        ERROR
    }

    NovelDiagnostic {
        text = text == null || text.isBlank() ? "\u6ca1\u6709\u8be6\u7ec6\u4fe1\u606f\u3002" : text;
    }

    static NovelDiagnostic info(String text) {
        return new NovelDiagnostic(Severity.INFO, text);
    }

    static NovelDiagnostic error(String text) {
        return new NovelDiagnostic(Severity.ERROR, text);
    }

    static NovelDiagnostic compilation(ExpressionCompiler.Compilation compilation) {
        return compilation.valid()
                ? info(compilation.message())
                : error("\u7f16\u8bd1\u5931\u8d25\n" + compilation.message());
    }

    static NovelDiagnostic runtime(ExpressionCompiler.Compilation compilation,
                                   RuntimeExpressionValidator.Result runtime) {
        return runtime.valid()
                ? info(compilation.message() + "  " + runtime.message())
                : error("\u8fd0\u884c\u73af\u5883\u68c0\u67e5\u5931\u8d25\n" + runtime.message());
    }
}
