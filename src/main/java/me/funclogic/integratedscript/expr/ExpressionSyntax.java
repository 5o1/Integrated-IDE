package me.funclogic.integratedscript.expr;

import java.util.List;

/** Package-private AST shared by the parser and plan lowerer. */
final class ExpressionSyntax {
    private ExpressionSyntax() {
    }

    sealed interface Statement permits ExpressionStatement, Assignment {
        int position();
    }

    record ExpressionStatement(int position, Expr expression) implements Statement {
    }

    record Assignment(int position, String name, Expr expression) implements Statement {
    }

    record Program(List<Statement> statements) {
    }

    sealed interface Expr permits Literal, Reference, GlobalCall, MemberCall {
        int position();
    }

    record Literal(int position, ExpressionCompiler.LiteralKind kind, String value) implements Expr {
    }

    record Reference(int position, String name) implements Expr {
    }

    record GlobalCall(int position, String name, List<Expr> arguments) implements Expr {
    }

    record MemberCall(int position, Expr receiver, String name, List<Expr> arguments) implements Expr {
    }
}
