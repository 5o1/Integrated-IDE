package me.funclogic.integratedide.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;

/**
 * Minecraft exposes no public cursor API for MultiLineEditBox. If that private
 * implementation detail changes, Novel mode remains usable and only
 * completion is disabled instead of crashing the screen.
 */
final class CompletionEditorAccess {
    private static final Field TEXT_FIELD = findTextField();

    private CompletionEditorAccess() {
    }

    static boolean isAvailable() {
        return TEXT_FIELD != null;
    }

    static int cursor(MultiLineEditBox editor) {
        MultilineTextField field = field(editor);
        return field == null ? editor.getValue().length() : field.cursor();
    }

    static Caret caret(MultiLineEditBox editor) {
        MultilineTextField field = field(editor);
        if (field == null) {
            String source = editor.getValue();
            int cursor = source.length();
            int lineStart = source.lastIndexOf('\n') + 1;
            int line = 0;
            for (int index = 0; index < lineStart; index++) {
                if (source.charAt(index) == '\n') {
                    line++;
                }
            }
            return new Caret(cursor, line, lineStart);
        }
        int line = field.getLineAtCursor();
        int cursor = field.cursor();
        return new Caret(cursor, line, visualLineStart(field, line, editor.getValue(), cursor));
    }

    /**
     * Returns the editor's wrapped visual-line starts. Annotation rendering
     * uses this exact text-field layout instead of pretending a source newline
     * is the same thing as a displayed line.
     */
    static List<VisualLine> visualLines(MultiLineEditBox editor) {
        MultilineTextField field = field(editor);
        if (field == null) {
            return physicalLines(editor.getValue());
        }
        try {
            List<VisualLine> lines = new ArrayList<>();
            Object views = field.iterateLines();
            if (!(views instanceof Iterable<?> iterable)) {
                return physicalLines(editor.getValue());
            }
            for (Object line : iterable) {
                int start = lineOffset(line, "beginIndex");
                int end = lineOffset(line, "endIndex");
                lines.add(new VisualLine(start, end, lines.size()));
            }
            return lines.isEmpty() ? physicalLines(editor.getValue()) : List.copyOf(lines);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return physicalLines(editor.getValue());
        }
    }

    static boolean setCursorListener(MultiLineEditBox editor, Runnable listener) {
        MultilineTextField field = field(editor);
        if (field == null) {
            return false;
        }
        field.setCursorListener(listener);
        return true;
    }

    static boolean replaceCurrentToken(MultiLineEditBox editor, String replacement) {
        MultilineTextField field = field(editor);
        if (field == null) {
            return false;
        }
        String source = editor.getValue();
        int cursor = field.cursor();
        int start = cursor;
        while (start > 0 && isTokenCharacter(source.charAt(start - 1))) {
            start--;
        }
        int end = cursor;
        while (end < source.length() && isTokenCharacter(source.charAt(end))) {
            end++;
        }
        editor.setValue(source.substring(0, start) + replacement + source.substring(end));
        field.seekCursor(Whence.ABSOLUTE, start + replacement.length());
        return true;
    }

    private static boolean isTokenCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == ':'
                || character == '/' || character == '.' || character == '$' || character == '@' || character == '#'
                || character == '"'
                || character == '{' || character == '}';
    }

    private static MultilineTextField field(MultiLineEditBox editor) {
        if (TEXT_FIELD == null) {
            return null;
        }
        try {
            return (MultilineTextField) TEXT_FIELD.get(editor);
        } catch (IllegalAccessException | ClassCastException error) {
            return null;
        }
    }

    private static Field findTextField() {
        for (Field field : MultiLineEditBox.class.getDeclaredFields()) {
            if (field.getType() == MultilineTextField.class) {
                try {
                    return field.trySetAccessible() ? field : null;
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static int visualLineStart(MultilineTextField field, int line, String source, int cursor) {
        try {
            return lineOffset(field.getLineView(line), "beginIndex");
        } catch (ReflectiveOperationException | RuntimeException error) {
            return source.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        }
    }

    /**
     * StringView is protected in the compiled Minecraft API even though its
     * accessors are public. Keep that implementation detail at this boundary.
     */
    private static int lineOffset(Object line, String accessor) throws ReflectiveOperationException {
        Method method = line.getClass().getMethod(accessor);
        method.trySetAccessible();
        return (int) method.invoke(line);
    }

    private static List<VisualLine> physicalLines(String source) {
        List<VisualLine> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                lines.add(new VisualLine(start, index, lines.size()));
                start = index + 1;
            }
        }
        lines.add(new VisualLine(start, source.length(), lines.size()));
        return List.copyOf(lines);
    }

    record Caret(int cursor, int visualLine, int lineStart) {
    }

    record VisualLine(int sourceStart, int sourceEnd, int visualIndex) {
    }
}
