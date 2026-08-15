package me.funclogic.integratedide.expr;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Finds the innermost unfinished function call around a cursor without
 * requiring the surrounding source to be a complete, compilable program.
 */
public final class PartialCallAnalysis {
    private PartialCallAnalysis() {
    }

    public record CallSite(String name, String receiver, int argumentIndex, boolean emptyArgument) {
    }

    public static Optional<CallSite> at(String source, int cursor) {
        String input = source == null ? "" : source;
        int limit = Math.max(0, Math.min(cursor, input.length()));
        Deque<OpenCall> calls = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;

        for (int index = 0; index < limit; index++) {
            char character = input.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
                continue;
            }
            if (character == '(') {
                calls.push(OpenCall.before(input, index));
                continue;
            }
            if (character == ')' && !calls.isEmpty()) {
                calls.pop();
                continue;
            }
            if (character == ',' && !calls.isEmpty()) {
                calls.peek().nextArgument(index);
            }
        }

        if (calls.isEmpty()) {
            return Optional.empty();
        }
        OpenCall current = calls.peek();
        if (current.name == null) {
            return Optional.empty();
        }
        return Optional.of(new CallSite(current.name, current.receiver, current.argumentIndex,
                onlyWhitespace(input, current.argumentStart, limit)));
    }

    private static boolean onlyWhitespace(String source, int start, int end) {
        for (int index = start; index < end; index++) {
            if (!Character.isWhitespace(source.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static final class OpenCall {
        private final String name;
        private final String receiver;
        private int argumentIndex;
        private int argumentStart;

        private OpenCall(String name, String receiver, int argumentStart) {
            this.name = name;
            this.receiver = receiver;
            this.argumentStart = argumentStart;
        }

        static OpenCall before(String source, int openingParenthesis) {
            int nameEnd = skipWhitespaceLeft(source, openingParenthesis - 1);
            int nameStart = nameEnd;
            while (nameStart >= 0 && isIdentifierPart(source.charAt(nameStart))) {
                nameStart--;
            }
            if (nameStart == nameEnd) {
                return new OpenCall(null, null, openingParenthesis + 1);
            }
            String name = source.substring(nameStart + 1, nameEnd + 1);
            int beforeName = skipWhitespaceLeft(source, nameStart);
            String receiver = null;
            if (beforeName >= 0 && source.charAt(beforeName) == '.') {
                int receiverEnd = skipWhitespaceLeft(source, beforeName - 1);
                int receiverStart = receiverEnd;
                while (receiverStart >= 0 && isReceiverPart(source.charAt(receiverStart))) {
                    receiverStart--;
                }
                if (receiverStart < receiverEnd) {
                    receiver = source.substring(receiverStart + 1, receiverEnd + 1);
                }
            }
            return new OpenCall(name, receiver, openingParenthesis + 1);
        }

        void nextArgument(int comma) {
            argumentIndex++;
            argumentStart = comma + 1;
        }

        private static int skipWhitespaceLeft(String source, int index) {
            while (index >= 0 && Character.isWhitespace(source.charAt(index))) {
                index--;
            }
            return index;
        }

        private static boolean isIdentifierPart(char character) {
            return character == '_' || Character.isLetterOrDigit(character);
        }

        private static boolean isReceiverPart(char character) {
            return isIdentifierPart(character) || character == '{' || character == '}';
        }
    }
}
