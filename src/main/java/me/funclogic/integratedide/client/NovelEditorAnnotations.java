package me.funclogic.integratedide.client;

import java.util.List;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;

/**
 * Visual-only layers painted over the standard multi-line editor.  Keeping
 * these markers outside the controller avoids coupling cache state, input
 * routing, and text geometry in one class.
 */
final class NovelEditorAnnotations {
    private static final int GUIDE_LINES = 8;
    private static final float GUIDE_SCALE = 0.75F;
    private static final int GUIDE_LINE_HEIGHT = 7;

    private final Font font;
    private final MultiLineEditBox editor;
    private final int padding;
    private final int maximumCharacters;
    private final int nativeCardSlotX;
    private final int nativeCardSlotY;
    private final int cardSlotSize;

    NovelEditorAnnotations(Font font, MultiLineEditBox editor, int padding, int maximumCharacters,
                           int nativeCardSlotX, int nativeCardSlotY, int cardSlotSize) {
        this.font = font;
        this.editor = editor;
        this.padding = padding;
        this.maximumCharacters = maximumCharacters;
        this.nativeCardSlotX = nativeCardSlotX;
        this.nativeCardSlotY = nativeCardSlotY;
        this.cardSlotSize = cardSlotSize;
    }

    void renderCounterBackground(GuiGraphicsExtractor graphics) {
        String counter = editor.getValue().length() + "/" + maximumCharacters;
        int right = editor.getRight() - 2;
        int bottom = editor.getBottom() - 2;
        int x = right - font.width(counter) - 4;
        int y = bottom - font.lineHeight - 2;
        graphics.fill(x - 2, y - 1, right, bottom, 0xD0101010);
    }

    /** The counter text is painted before the editor widget, leaving typed text on top of it. */
    void renderCounter(GuiGraphicsExtractor graphics) {
        String counter = editor.getValue().length() + "/" + maximumCharacters;
        graphics.text(font, counter, editor.getRight() - font.width(counter) - 4,
                editor.getBottom() - font.lineHeight - 3, 0xFFA0A0A0, false);
    }

    void render(GuiGraphicsExtractor graphics, ExpressionCompiler.Compilation compilation,
                NovelCompilationCache.Reconciliation reconciliation, List<NovelCompilationCache.MissingNode> missing,
                int requiredCards, int mouseX, int mouseY) {
        Player player = Minecraft.getInstance().player;
        renderCardCapacity(graphics, player, requiredCards);
        renderEmptyGuide(graphics);
        renderExternalReferences(graphics, compilation, player);
        renderMissingCachedNodeMarkers(graphics, missing);
        renderErrorUnderline(graphics, compilation);
        renderHoveredRootId(graphics, compilation, reconciliation, player, mouseX, mouseY);
    }

    private void renderEmptyGuide(GuiGraphicsExtractor graphics) {
        if (!editor.getValue().isEmpty()) {
            return;
        }
        int x = editor.getX() + padding;
        int y = editor.getY() + padding;
        int maxWidth = Math.round((editor.getWidth() - padding * 2) / GUIDE_SCALE);
        int visualLine = 0;
        graphics.pose().pushMatrix();
        graphics.pose().scale(GUIDE_SCALE, GUIDE_SCALE);
        for (int line = 1; line <= GUIDE_LINES; line++) {
            Component shortcut = line == 1 ? IntegratedIdeKeyMappings.COMPILE_NOVEL.getTranslatedKeyMessage()
                    : line == 2 ? IntegratedIdeKeyMappings.REQUEST_COMPLETION.getTranslatedKeyMessage() : Component.empty();
            List<FormattedCharSequence> wrapped = font.split(
                    Component.translatable("integratedide.guide." + line, shortcut), maxWidth);
            for (FormattedCharSequence visualLineText : wrapped) {
                graphics.text(font, visualLineText, Math.round(x / GUIDE_SCALE),
                        Math.round((y + visualLine * GUIDE_LINE_HEIGHT) / GUIDE_SCALE), 0xFF8A8A8A, false);
                visualLine++;
            }
        }
        graphics.pose().popMatrix();
    }

    private void renderCardCapacity(GuiGraphicsExtractor graphics, Player player, int required) {
        int available = CardBuildDriver.countBlankVariableCards(player);
        int freeSlots = CardInventory.countEmptyPlayerSlots(player);
        String capacity = required + "/" + available + "/" + freeSlots;
        int left = nativeCardSlotX + 1;
        int right = nativeCardSlotX + cardSlotSize - 1;
        int top = nativeCardSlotY + 1;
        int bottom = nativeCardSlotY + cardSlotSize - 1;
        float scale = Math.min((right - left - 2F) / font.width(capacity),
                (bottom - top - 2F) / font.lineHeight);
        int width = Math.round(font.width(capacity) * scale);
        int height = Math.round(font.lineHeight * scale);
        int x = right - width - 1;
        int y = bottom - height - 1;
        int color = available >= required && freeSlots >= required ? 0xFF9CCF9C : 0xFFE08080;
        graphics.fill(Math.max(left, x - 1), Math.max(top, y - 1), right, bottom, 0xD0101010);
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        graphics.text(font, capacity, Math.round(x / scale), Math.round(y / scale), color, false);
        graphics.pose().popMatrix();
    }

    private void renderExternalReferences(GuiGraphicsExtractor graphics, ExpressionCompiler.Compilation compilation,
                                          Player player) {
        if (compilation == null || !compilation.valid()) {
            return;
        }
        for (ExpressionCompiler.CardStep step : compilation.steps()) {
            if (step.kind() != ExpressionCompiler.StepKind.EXTERNAL_REFERENCE) {
                continue;
            }
            int id = Integer.parseInt(step.value());
            boolean available = CardInventory.findVariableCardById(player, id, step.outputTypeId()) != null;
            renderTextRange(graphics, step.sourceStart(), explicitReferenceEnd(step),
                    available ? 0xFF55AAFF : 0xFFFF5555);
        }
    }

    private void renderMissingCachedNodeMarkers(GuiGraphicsExtractor graphics,
                                                List<NovelCompilationCache.MissingNode> missing) {
        for (NovelCompilationCache.MissingNode node : missing) {
            renderRangeUnderline(graphics, node.step().sourceStart(), node.step().sourceEnd(), 0xFFE06060);
        }
    }

    private void renderErrorUnderline(GuiGraphicsExtractor graphics, ExpressionCompiler.Compilation compilation) {
        if (compilation == null || compilation.valid() || compilation.errorPosition() < 0) {
            return;
        }
        String source = editor.getValue();
        int position = Math.min(compilation.errorPosition(), source.length());
        TextLocation location = textLocation(position);
        int x = location.x();
        int y = location.y();
        if (y >= editor.getY() && y < editor.getBottom()) {
            graphics.fill(x, y + font.lineHeight - 1, x + 3, y + font.lineHeight, 0xFFE06060);
        }
    }

    private void renderHoveredRootId(GuiGraphicsExtractor graphics, ExpressionCompiler.Compilation compilation,
                                     NovelCompilationCache.Reconciliation reconciliation, Player player,
                                     int mouseX, int mouseY) {
        if (!insideEditor(mouseX, mouseY) || compilation == null || !compilation.valid()) {
            return;
        }
        String source = editor.getValue();
        int relativeY = mouseY - editor.getY() - padding + (int) editor.scrollAmount();
        if (relativeY < 0) {
            return;
        }
        List<CompletionEditorAccess.VisualLine> lines = CompletionEditorAccess.visualLines(editor);
        int visualLine = relativeY / font.lineHeight;
        if (visualLine < 0 || visualLine >= lines.size()) {
            return;
        }
        int lineStart = lines.get(visualLine).sourceStart();
        int lineEnd = lines.get(visualLine).sourceEnd();
        ExpressionCompiler.StatementRoot root = compilation.statementRoots().stream()
                .filter(candidate -> candidate.sourceStart() < lineEnd && candidate.sourceEnd() > lineStart)
                .findFirst()
                .orElse(null);
        if (root == null) {
            return;
        }
        ExpressionCompiler.CardStep rootStep = compilation.steps().stream()
                .filter(candidate -> candidate.id().equals(root.stepId()))
                .findFirst()
                .orElse(null);
        if (rootStep == null) {
            return;
        }

        int id;
        int color;
        if (rootStep.kind() == ExpressionCompiler.StepKind.EXTERNAL_REFERENCE) {
            id = Integer.parseInt(rootStep.value());
            boolean available = CardInventory.findVariableCardById(player, id, rootStep.outputTypeId()) != null;
            color = available ? 0xFF55AAFF : 0xFFFF5555;
        } else {
            NovelCompilationCache.CachedNode cached = reconciliation == null ? null : reconciliation.match(root.stepId());
            if (cached == null || cached.variableCardId < 0) {
                return;
            }
            id = cached.variableCardId;
            color = 0xFFE0E0E0;
        }

        TextLocation location = textLocation(lineStart);
        if (location.y() < editor.getY() || location.y() >= editor.getBottom()) {
            return;
        }
        String label = "{" + id + "}";
        int x = editor.getX() + 1;
        int width = font.width(label);
        graphics.fill(x - 1, location.y() - 1, x + width + 2, location.y() + font.lineHeight + 1, 0xD0101010);
        graphics.text(font, label, x, location.y(), color, false);
    }

    private int explicitReferenceEnd(ExpressionCompiler.CardStep step) {
        String source = editor.getValue();
        int closingBrace = source.indexOf('}', Math.max(0, step.sourceStart()));
        return closingBrace < 0 ? step.sourceEnd() : closingBrace + 1;
    }

    private void renderTextRange(GuiGraphicsExtractor graphics, int start, int end, int color) {
        String source = editor.getValue();
        int safeStart = Math.max(0, Math.min(start, source.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, source.length()));
        List<CompletionEditorAccess.VisualLine> lines = CompletionEditorAccess.visualLines(editor);
        for (int index = 0; index < lines.size(); index++) {
            int lineStart = lines.get(index).sourceStart();
            int lineEnd = lines.get(index).sourceEnd();
            int segmentStart = Math.max(safeStart, lineStart);
            int segmentEnd = Math.min(safeEnd, lineEnd);
            if (segmentStart >= segmentEnd) {
                continue;
            }
            TextLocation location = textLocation(segmentStart);
            if (location.y() >= editor.getY() && location.y() < editor.getBottom()) {
                graphics.text(font, source.substring(segmentStart, segmentEnd), location.x(), location.y(), color, false);
            }
        }
    }

    private void renderRangeUnderline(GuiGraphicsExtractor graphics, int start, int end, int color) {
        String source = editor.getValue();
        int safeStart = Math.max(0, Math.min(start, source.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, source.length()));
        List<CompletionEditorAccess.VisualLine> lines = CompletionEditorAccess.visualLines(editor);
        for (int index = 0; index < lines.size(); index++) {
            int lineStart = lines.get(index).sourceStart();
            int lineEnd = lines.get(index).sourceEnd();
            int segmentStart = Math.max(safeStart, lineStart);
            int segmentEnd = Math.min(safeEnd, lineEnd);
            if (segmentStart >= segmentEnd) {
                continue;
            }
            TextLocation location = textLocation(segmentStart);
            if (location.y() >= editor.getY() && location.y() < editor.getBottom()) {
                int width = font.width(source.substring(segmentStart, segmentEnd));
                graphics.fill(location.x(), location.y() + font.lineHeight - 1, location.x() + Math.max(3, width),
                        location.y() + font.lineHeight, color);
            }
        }
    }

    private TextLocation textLocation(int position) {
        String source = editor.getValue();
        int safePosition = Math.max(0, Math.min(position, source.length()));
        List<CompletionEditorAccess.VisualLine> lines = CompletionEditorAccess.visualLines(editor);
        CompletionEditorAccess.VisualLine line = lines.getFirst();
        for (CompletionEditorAccess.VisualLine candidate : lines) {
            if (candidate.sourceStart() > safePosition) {
                break;
            }
            line = candidate;
        }
        int x = editor.getX() + padding + font.width(source.substring(line.sourceStart(), safePosition));
        int y = editor.getY() + padding + line.visualIndex() * font.lineHeight - (int) editor.scrollAmount();
        return new TextLocation(safePosition, x, y);
    }

    private boolean insideEditor(double mouseX, double mouseY) {
        return mouseX >= editor.getX() && mouseX < editor.getRight()
                && mouseY >= editor.getY() && mouseY < editor.getBottom();
    }

    private record TextLocation(int position, int x, int y) {
    }
}
