package me.funclogic.integratedide.expr;

import java.util.ArrayList;
import java.util.List;

/** Parses the token stream into the compact AST used by the plan lowerer. */
final class ExpressionParser {
    private final List<ExpressionToken> tokens;
    private int index;

    ExpressionParser(String source) {
        this.tokens = new ExpressionLexer(source).lex();
    }

    ExpressionSyntax.Program parseProgram() {
        List<ExpressionSyntax.Statement> statements = new ArrayList<>();
        while (!check(ExpressionToken.Type.EOF)) {
            while (match(ExpressionToken.Type.NEWLINE)) {
                // Blank lines are deliberately ignored.
            }
            if (check(ExpressionToken.Type.EOF)) {
                break;
            }
            int position = current().position();
            if (check(ExpressionToken.Type.LBRACE) && peek(1).type() == ExpressionToken.Type.IDENTIFIER
                    && peek(2).type() == ExpressionToken.Type.RBRACE && peek(3).type() == ExpressionToken.Type.EQUALS) {
                advance();
                String name = expect(ExpressionToken.Type.IDENTIFIER, "Expected a temporary variable name.").text();
                expect(ExpressionToken.Type.RBRACE, "Expected '}' after temporary variable name.");
                expect(ExpressionToken.Type.EQUALS, "Expected '=' after temporary variable name.");
                ExpressionSyntax.Expr expression = parseExpression();
                statements.add(new ExpressionSyntax.Assignment(position, expression.end(), name, expression));
            } else {
                ExpressionSyntax.Expr expression = parseExpression();
                statements.add(new ExpressionSyntax.ExpressionStatement(position, expression.end(), expression));
            }
            if (!check(ExpressionToken.Type.EOF) && !match(ExpressionToken.Type.NEWLINE)) {
                throw error(current().position(), "Statements must be separated by a newline.");
            }
        }
        return new ExpressionSyntax.Program(List.copyOf(statements));
    }

    private ExpressionSyntax.Expr parseExpression() {
        ExpressionSyntax.Expr result = parsePrimary();
        while (match(ExpressionToken.Type.DOT)) {
            ExpressionToken name = expect(ExpressionToken.Type.IDENTIFIER, "Expected a member function name after '.'.");
            Arguments arguments = parseArguments();
            result = new ExpressionSyntax.MemberCall(result.position(), arguments.end(), result, name.text(), arguments.values());
        }
        return result;
    }

    private ExpressionSyntax.Expr parsePrimary() {
        ExpressionToken token = current();
        return switch (token.type()) {
            case STRING -> literal(token, ExpressionCompiler.LiteralKind.STRING);
            case INTEGER -> literal(token, ExpressionCompiler.LiteralKind.INTEGER);
            case DECIMAL -> literal(token, ExpressionCompiler.LiteralKind.DECIMAL);
            case BOOLEAN -> literal(token, ExpressionCompiler.LiteralKind.BOOLEAN);
            case ITEM -> literal(token, ExpressionCompiler.LiteralKind.ITEM);
            case MOD -> literal(token, ExpressionCompiler.LiteralKind.MOD);
            case TAG -> literal(token, ExpressionCompiler.LiteralKind.TAG);
            case LBRACE -> parseReference(token);
            case IDENTIFIER -> parseGlobalCall(token);
            default -> throw error(token.position(), "Expected a literal, {temporary}, or function call.");
        };
    }

    private ExpressionSyntax.Literal literal(ExpressionToken token, ExpressionCompiler.LiteralKind kind) {
        advance();
        return new ExpressionSyntax.Literal(token.position(), current().position(), kind, token.text());
    }

    private ExpressionSyntax.Expr parseReference(ExpressionToken token) {
        advance();
        if (check(ExpressionToken.Type.INTEGER)) {
            ExpressionToken id = advance();
            expect(ExpressionToken.Type.RBRACE, "Expected '}' after the Variable Card ID.");
            try {
                return new ExpressionSyntax.ExternalReference(token.position(), current().position(),
                        Integer.parseInt(id.text()));
            } catch (NumberFormatException error) {
                throw error(id.position(), "Variable Card IDs must fit in a signed 32-bit integer.");
            }
        }
        ExpressionToken name = expect(ExpressionToken.Type.IDENTIFIER, "Expected a temporary variable name or card ID.");
        expect(ExpressionToken.Type.RBRACE, "Expected '}' after temporary variable name.");
        return new ExpressionSyntax.Reference(token.position(), current().position(), name.text());
    }

    private ExpressionSyntax.GlobalCall parseGlobalCall(ExpressionToken token) {
        advance();
        Arguments arguments = parseArguments();
        return new ExpressionSyntax.GlobalCall(token.position(), arguments.end(), token.text(), arguments.values());
    }

    private Arguments parseArguments() {
        expect(ExpressionToken.Type.LPAREN, "Expected '(' after function name.");
        List<ExpressionSyntax.Expr> arguments = new ArrayList<>();
        if (!check(ExpressionToken.Type.RPAREN)) {
            do {
                arguments.add(parseExpression());
            } while (match(ExpressionToken.Type.COMMA));
        }
        expect(ExpressionToken.Type.RPAREN, "Expected ')' after function arguments.");
        return new Arguments(List.copyOf(arguments), current().position());
    }

    private ExpressionToken current() {
        return tokens.get(index);
    }

    private ExpressionToken peek(int offset) {
        return tokens.get(Math.min(index + offset, tokens.size() - 1));
    }

    private boolean check(ExpressionToken.Type type) {
        return current().type() == type;
    }

    private boolean match(ExpressionToken.Type type) {
        if (!check(type)) {
            return false;
        }
        index++;
        return true;
    }

    private ExpressionToken advance() {
        ExpressionToken token = current();
        if (!check(ExpressionToken.Type.EOF)) {
            index++;
        }
        return token;
    }

    private ExpressionToken expect(ExpressionToken.Type type, String message) {
        if (!check(type)) {
            throw error(current().position(), message);
        }
        return advance();
    }

    private static ExpressionCompileError error(int position, String message) {
        return new ExpressionCompileError(position, message);
    }

    private record Arguments(List<ExpressionSyntax.Expr> values, int end) {
    }
}
