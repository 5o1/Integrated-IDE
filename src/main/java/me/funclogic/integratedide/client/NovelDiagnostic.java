package me.funclogic.integratedide.client;

import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.network.chat.Component;

/** A durable, user-facing message for the Novel editor's diagnostic area. */
record NovelDiagnostic(Severity severity, Component text) {
    enum Severity {
        INFO,
        ERROR
    }

    NovelDiagnostic {
        text = text == null || text.getString().isBlank() ? Component.translatable("integratedide.diagnostic.empty") : text;
    }

    static NovelDiagnostic info(Component text) {
        return new NovelDiagnostic(Severity.INFO, text);
    }

    static NovelDiagnostic info(String text) {
        return info(Component.literal(text));
    }

    static NovelDiagnostic error(Component text) {
        return new NovelDiagnostic(Severity.ERROR, text);
    }

    static NovelDiagnostic error(String text) {
        return error(Component.literal(text));
    }

    static NovelDiagnostic compilation(ExpressionCompiler.Compilation compilation) {
        return compilation.valid()
                ? info(compilation.message())
                : error(Component.translatable("integratedide.diagnostic.compilation", compilation.message()));
    }

    static NovelDiagnostic runtime(ExpressionCompiler.Compilation compilation,
                                   RuntimeExpressionValidator.Result runtime) {
        return runtime.valid()
                ? info(compilation.message() + "  " + runtime.message())
                : error(Component.translatable("integratedide.diagnostic.runtime", runtime.message()));
    }
}
