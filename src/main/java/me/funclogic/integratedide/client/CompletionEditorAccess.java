package me.funclogic.integratedide.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
        try {
            Field field = MultiLineEditBox.class.getDeclaredField("textField");
            return field.trySetAccessible() ? field : null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static int visualLineStart(MultilineTextField field, int line, String source, int cursor) {
        try {
            Method getLineView = MultilineTextField.class.getMethod("getLineView", int.class);
            getLineView.trySetAccessible();
            Object view = getLineView.invoke(field, line);
            Method beginIndex = view.getClass().getMethod("beginIndex");
            beginIndex.trySetAccessible();
            return (int) beginIndex.invoke(view);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return source.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        }
    }

    record Caret(int cursor, int visualLine, int lineStart) {
    }
}
