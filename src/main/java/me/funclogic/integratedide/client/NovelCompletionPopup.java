package me.funclogic.integratedide.client;

import java.util.ArrayList;
import java.util.List;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;

/**
 * Layout, hit testing, and rendering for the floating Novel completion and
 * signature popup.  It has no compiler or editor-session state of its own.
 */
final class NovelCompletionPopup {
    enum Mode {
        NONE, SIGNATURE, COMPLETIONS
    }

    private final Font font;
    private final MultiLineEditBox editor;
    private final int minY;
    private final int maxY;
    private final int editorPadding;
    private final int maxCompletions;

    NovelCompletionPopup(Font font, MultiLineEditBox editor, int minY, int maxY, int editorPadding,
                         int maxCompletions) {
        this.font = font;
        this.editor = editor;
        this.minY = minY;
        this.maxY = maxY;
        this.editorPadding = editorPadding;
        this.maxCompletions = maxCompletions;
    }

    void render(GuiGraphicsExtractor graphics, Mode mode, List<LogicProgrammerCatalog.Completion> completions,
                LogicProgrammerCatalog.Signature signature, int selectedCompletion) {
        Popup popup = popup(mode, completions, signature);
        if (popup == null) {
            return;
        }
        graphics.fill(popup.x(), popup.y(), popup.x() + popup.width(), popup.y() + popup.height(), 0xF0181818);
        graphics.outline(popup.x(), popup.y(), popup.width(), popup.height(), 0xFF777777);
        int rowY = popup.y() + 3;
        for (PopupRow row : popup.rows()) {
            boolean selected = mode == Mode.COMPLETIONS && row.completionIndex() == selectedCompletion;
            if (selected) {
                graphics.fill(popup.x() + 1, rowY - 1, popup.x() + popup.width() - 1, rowY + row.height() - 1,
                        0xFF4A4A4A);
            }
            int lineY = rowY;
            for (PopupLine line : row.lines()) {
                int availableWidth = popup.width() - 8 - line.indent();
                String visible = font.plainSubstrByWidth(line.text(), Math.max(1, availableWidth));
                int color = selected || line.active() ? 0xFFFFD080 : 0xFFE0E0E0;
                graphics.text(font, visible, popup.x() + 4 + line.indent(), lineY, color, false);
                lineY += lineHeight();
            }
            rowY += row.height();
        }
    }

    int completionAt(Mode mode, List<LogicProgrammerCatalog.Completion> completions,
                     LogicProgrammerCatalog.Signature signature, double mouseX, double mouseY) {
        Popup popup = popup(mode, completions, signature);
        if (popup == null || mouseX < popup.x() || mouseX >= popup.x() + popup.width()
                || mouseY < popup.y() || mouseY >= popup.y() + popup.height()) {
            return -1;
        }
        int rowY = popup.y() + 3;
        for (PopupRow row : popup.rows()) {
            if (mouseY >= rowY && mouseY < rowY + row.height()) {
                return row.completionIndex();
            }
            rowY += row.height();
        }
        return -1;
    }

    private Popup popup(Mode mode, List<LogicProgrammerCatalog.Completion> completions,
                        LogicProgrammerCatalog.Signature signature) {
        if (mode == Mode.NONE || mode == Mode.COMPLETIONS && completions.isEmpty()
                || mode == Mode.SIGNATURE && signature == null) {
            return null;
        }
        CompletionEditorAccess.Caret caret = CompletionEditorAccess.caret(editor);
        int anchorY = editor.getY() + editorPadding + caret.visualLine() * font.lineHeight
                - (int) editor.scrollAmount();
        int maximumRows = mode == Mode.COMPLETIONS ? Math.min(maxCompletions, completions.size()) : 1;
        for (int count = maximumRows; count > 0; count--) {
            List<PopupRow> rows = popupRows(mode, completions, signature, count);
            int height = 4;
            for (PopupRow row : rows) {
                height += row.height();
            }
            if (height > maxY - minY) {
                continue;
            }
            int below = anchorY + font.lineHeight + 2;
            int above = anchorY - height - 2;
            int y;
            if (below + height <= maxY) {
                y = below;
            } else if (above >= minY) {
                y = above;
            } else {
                // It may overlap the editor, but never the mode tab, status,
                // native card slot, or player inventory.
                y = clamp(below, minY, maxY - height);
            }
            return new Popup(editor.getX(), y, editor.getWidth(), height, rows);
        }
        return null;
    }

    private List<PopupRow> popupRows(Mode mode, List<LogicProgrammerCatalog.Completion> completions,
                                      LogicProgrammerCatalog.Signature signature, int count) {
        if (mode == Mode.SIGNATURE && signature != null) {
            List<PopupLine> lines = signatureLines(signature);
            return List.of(new PopupRow(-1, lines, lines.size() * lineHeight() + 2));
        }
        List<PopupRow> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<PopupLine> lines = index == 0 ? expandedCompletionLines(completions.get(index))
                    : List.of(new PopupLine(0, collapsedCompletionText(completions.get(index)), false));
            rows.add(new PopupRow(index, lines, lines.size() * lineHeight() + 2));
        }
        return rows;
    }

    private List<PopupLine> expandedCompletionLines(LogicProgrammerCatalog.Completion completion) {
        if (completion.function() == null) {
            return List.of(new PopupLine(0, completion.insertion() + "  " + completion.detail(), false));
        }
        String opening = completion.insertion();
        List<ExpressionCompiler.TypeInfo> inputs = completion.function().inputTypes();
        int firstArgument = Math.min(completion.receiverArguments(), inputs.size());
        if (firstArgument == inputs.size()) {
            return List.of(new PopupLine(0, opening + ")", false));
        }
        StringBuilder singleLine = new StringBuilder(opening);
        for (int index = firstArgument; index < inputs.size(); index++) {
            if (index > firstArgument) {
                singleLine.append(", ");
            }
            singleLine.append(inputs.get(index).displayName());
        }
        singleLine.append(')');
        if (font.width(singleLine.toString()) <= editor.getWidth() - 8) {
            return List.of(new PopupLine(0, singleLine.toString(), false));
        }
        List<PopupLine> lines = new ArrayList<>();
        int indent = font.width(opening);
        for (int index = firstArgument; index < inputs.size(); index++) {
            boolean last = index == inputs.size() - 1;
            String argument = inputs.get(index).displayName() + (last ? ")" : ",");
            lines.add(new PopupLine(index == firstArgument ? 0 : indent,
                    index == firstArgument ? opening + argument : argument, false));
        }
        return lines;
    }

    private List<PopupLine> signatureLines(LogicProgrammerCatalog.Signature signature) {
        List<ExpressionCompiler.TypeInfo> inputs = signature.function().inputTypes();
        int firstArgument = Math.min(signature.receiverArguments(), inputs.size());
        String opening = signature.invocation() + "(";
        if (firstArgument == inputs.size()) {
            return List.of(new PopupLine(0, opening + ")", true));
        }
        StringBuilder singleLine = new StringBuilder(opening);
        for (int index = firstArgument; index < inputs.size(); index++) {
            if (index > firstArgument) {
                singleLine.append(", ");
            }
            singleLine.append(inputs.get(index).displayName());
        }
        singleLine.append(')');
        if (font.width(singleLine.toString()) <= editor.getWidth() - 8) {
            return List.of(new PopupLine(0, singleLine.toString(), true));
        }
        List<PopupLine> lines = new ArrayList<>();
        int indent = font.width(opening);
        int activeInput = firstArgument + signature.activeArgument();
        for (int index = firstArgument; index < inputs.size(); index++) {
            boolean last = index == inputs.size() - 1;
            String argument = inputs.get(index).displayName() + (last ? ")" : ",");
            lines.add(new PopupLine(index == firstArgument ? 0 : indent,
                    index == firstArgument ? opening + argument : argument, index == activeInput));
        }
        return lines;
    }

    private String collapsedCompletionText(LogicProgrammerCatalog.Completion completion) {
        if (completion.function() == null) {
            return completion.insertion() + "  " + completion.detail();
        }
        String insertion = completion.insertion();
        return insertion.endsWith("(") ? insertion.substring(0, insertion.length() - 1) + "(...)" : insertion;
    }

    private int lineHeight() {
        return font.lineHeight + 1;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Popup(int x, int y, int width, int height, List<PopupRow> rows) {
    }

    private record PopupRow(int completionIndex, List<PopupLine> lines, int height) {
    }

    private record PopupLine(int indent, String text, boolean active) {
    }
}
