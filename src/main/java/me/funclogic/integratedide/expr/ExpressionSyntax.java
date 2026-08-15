package me.funclogic.integratedide.expr;

import java.util.List;

/** Package-private AST shared by the parser and plan lowerer. */
final class ExpressionSyntax {
    private ExpressionSyntax() {
    }

    sealed interface Statement permits ExpressionStatement, Assignment {
        int position();

        int end();
    }

    record ExpressionStatement(int position, int end, Expr expression) implements Statement {
    }

    record Assignment(int position, int end, String name, Expr expression) implements Statement {
    }

    record Program(List<Statement> statements) {
    }

    sealed interface Expr permits Literal, Reference, ExternalReference, GlobalCall, MemberCall {
        int position();

        int end();
    }

    record Literal(int position, int end, ExpressionCompiler.LiteralKind kind, String value) implements Expr {
    }

    record Reference(int position, int end, String name) implements Expr {
    }

    /** An explicit reference to a Variable Card that must be in the player's inventory. */
    record ExternalReference(int position, int end, int variableCardId) implements Expr {
    }

    record GlobalCall(int position, int end, String name, List<Expr> arguments) implements Expr {
    }

    record MemberCall(int position, int end, Expr receiver, String name, List<Expr> arguments) implements Expr {
    }
}
