package me.funclogic.integratedide.expr;

import java.util.ArrayList;
import java.util.List;

/** Turns source text into position-aware tokens without applying grammar rules. */
final class ExpressionLexer {
    private final String source;

    ExpressionLexer(String source) {
        this.source = source;
    }

    List<ExpressionToken> lex() {
        List<ExpressionToken> tokens = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            char character = source.charAt(cursor);
            if (character == ' ' || character == '\t') {
                cursor++;
                continue;
            }
            if (character == '\r' || character == '\n') {
                int start = cursor++;
                if (character == '\r' && cursor < source.length() && source.charAt(cursor) == '\n') {
                    cursor++;
                }
                tokens.add(new ExpressionToken(ExpressionToken.Type.NEWLINE, "\\n", start));
                continue;
            }
            ExpressionToken.Type punctuation = punctuation(character);
            if (punctuation != null) {
                tokens.add(new ExpressionToken(punctuation, Character.toString(character), cursor++));
                continue;
            }
            if (character == '"') {
                cursor = string(tokens, cursor);
                continue;
            }
            if (character == '$' || character == '@' || character == '#') {
                throw error(cursor, "Resource literals must be wrapped in double quotes, for example \"$minecraft:stone\".");
            }
            if (Character.isDigit(character) || (character == '-' && cursor + 1 < source.length()
                    && Character.isDigit(source.charAt(cursor + 1)))) {
                cursor = number(tokens, cursor);
                continue;
            }
            if (isIdentifierStart(character)) {
                cursor = identifier(tokens, cursor);
                continue;
            }
            throw error(cursor, "Unexpected character '" + character + "'.");
        }
        tokens.add(new ExpressionToken(ExpressionToken.Type.EOF, "", source.length()));
        return List.copyOf(tokens);
    }

    private int string(List<ExpressionToken> tokens, int cursor) {
        int start = cursor++;
        char marker = cursor < source.length() ? source.charAt(cursor) : '\0';
        ExpressionToken.Type type = resourceType(marker);
        if (type != ExpressionToken.Type.STRING) {
            cursor++;
        }
        StringBuilder value = new StringBuilder();
        boolean closed = false;
        while (cursor < source.length()) {
            char current = source.charAt(cursor++);
            if (current == '"') {
                closed = true;
                break;
            }
            if (current == '\\' && cursor < source.length()) {
                char escaped = source.charAt(cursor++);
                value.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case '"', '\\' -> escaped;
                    default -> escaped;
                });
            } else {
                value.append(current);
            }
        }
        if (!closed) {
            throw error(start, "Unterminated string literal.");
        }
        if (type != ExpressionToken.Type.STRING) {
            if (value.isEmpty()) {
                throw error(start, "Expected an identifier after '" + marker + "'.");
            }
            for (int index = 0; index < value.length(); index++) {
                if (!isResourceCharacter(value.charAt(index))) {
                    throw error(start + 2 + index, "Invalid character in resource literal.");
                }
            }
        }
        tokens.add(new ExpressionToken(type, value.toString(), start));
        return cursor;
    }

    private static ExpressionToken.Type resourceType(char marker) {
        return switch (marker) {
            case '$' -> ExpressionToken.Type.ITEM;
            case '@' -> ExpressionToken.Type.MOD;
            case '#' -> ExpressionToken.Type.TAG;
            default -> ExpressionToken.Type.STRING;
        };
    }

    private int number(List<ExpressionToken> tokens, int cursor) {
        int start = cursor++;
        while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) {
            cursor++;
        }
        boolean decimal = false;
        if (cursor + 1 < source.length() && source.charAt(cursor) == '.'
                && Character.isDigit(source.charAt(cursor + 1))) {
            decimal = true;
            cursor++;
            while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) {
                cursor++;
            }
        }
        tokens.add(new ExpressionToken(decimal ? ExpressionToken.Type.DECIMAL : ExpressionToken.Type.INTEGER,
                source.substring(start, cursor), start));
        return cursor;
    }

    private int identifier(List<ExpressionToken> tokens, int cursor) {
        int start = cursor++;
        while (cursor < source.length() && isIdentifierPart(source.charAt(cursor))) {
            cursor++;
        }
        String text = source.substring(start, cursor);
        ExpressionToken.Type type = text.equals("true") || text.equals("false")
                ? ExpressionToken.Type.BOOLEAN : ExpressionToken.Type.IDENTIFIER;
        tokens.add(new ExpressionToken(type, text, start));
        return cursor;
    }

    private static ExpressionToken.Type punctuation(char character) {
        return switch (character) {
            case '{' -> ExpressionToken.Type.LBRACE;
            case '}' -> ExpressionToken.Type.RBRACE;
            case '.' -> ExpressionToken.Type.DOT;
            case '(' -> ExpressionToken.Type.LPAREN;
            case ')' -> ExpressionToken.Type.RPAREN;
            case ',' -> ExpressionToken.Type.COMMA;
            case '=' -> ExpressionToken.Type.EQUALS;
            default -> null;
        };
    }

    private static boolean isIdentifierStart(char character) {
        return character == '_' || Character.isLetter(character);
    }

    private static boolean isIdentifierPart(char character) {
        return isIdentifierStart(character) || Character.isDigit(character);
    }

    private static boolean isResourceCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == '.'
                || character == ':' || character == '/';
    }

    private static ExpressionCompileError error(int position, String message) {
        return new ExpressionCompileError(position, message);
    }
}
