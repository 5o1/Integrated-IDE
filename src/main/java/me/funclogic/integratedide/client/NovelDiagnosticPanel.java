package me.funclogic.integratedide.client;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Scrollable diagnostic area below the Novel editor.  It deliberately owns
 * only presentation state: compilation and build decisions remain in the
 * overlay controller.
 */
final class NovelDiagnosticPanel {
    private static final int HEIGHT = 32;
    private static final int PADDING = 3;
    private static final int SCROLLBAR_WIDTH = 3;
    private static final int SCROLLBAR_GAP = 2;
    private static final float SCALE = 0.75F;

    private final Font font;
    private final int left;
    private final int right;
    private final int top;
    private final int bottom;
    private NovelDiagnostic diagnostic = NovelDiagnostic.info("\u6309 Ctrl+Enter \u68c0\u67e5\u5e76\u751f\u6210");
    private int scrollLine;

    NovelDiagnosticPanel(Font font, int workX, int workY, int workHeight, int nativeCardSlotX) {
        this.font = font;
        this.left = workX + PADDING;
        this.right = nativeCardSlotX - PADDING;
        this.top = workY + workHeight - HEIGHT + PADDING;
        this.bottom = workY + workHeight - PADDING;
    }

    void setDiagnostic(NovelDiagnostic next) {
        diagnostic = next;
        // A replacement must begin at line one; otherwise a stale scrollbar
        // position can make a fresh compiler error invisible.
        scrollLine = 0;
    }

    void renderBackground(GuiGraphicsExtractor graphics) {
        graphics.fill(left - 2, top - 2, right + 2, bottom + 2, 0xD0101010);
        graphics.outline(left - 2, top - 2, right - left + 4, bottom - top + 4, 0xFF4A4A4A);
    }

    void render(GuiGraphicsExtractor graphics) {
        List<FormattedCharSequence> lines = lines();
        int visible = visibleLines();
        scrollLine = clamp(scrollLine, 0, Math.max(0, lines.size() - visible));
        int lineHeight = lineHeight();
        graphics.pose().pushMatrix();
        graphics.pose().scale(SCALE, SCALE);
        for (int index = 0; index < visible && scrollLine + index < lines.size(); index++) {
            graphics.text(font, lines.get(scrollLine + index), Math.round(left / SCALE),
                    Math.round((top + index * lineHeight) / SCALE), color(), false);
        }
        graphics.pose().popMatrix();
        renderScrollBar(graphics, lines.size(), visible);
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
    }

    boolean beginScrollDrag(double mouseX, double mouseY) {
        if (mouseX < scrollBarX() || mouseX >= right || mouseY < top || mouseY >= bottom) {
            return false;
        }
        updateScroll(mouseY);
        return true;
    }

    void dragTo(double mouseY) {
        updateScroll(mouseY);
    }

    void scroll(double delta) {
        scrollLine = clamp(scrollLine - (int) Math.signum(delta), 0, maxScroll());
    }

    private void renderScrollBar(GuiGraphicsExtractor graphics, int lineCount, int visible) {
        if (lineCount <= visible) {
            return;
        }
        int x = scrollBarX();
        int trackHeight = bottom - top;
        int thumbHeight = thumbHeight(lineCount, visible);
        int range = Math.max(1, trackHeight - thumbHeight);
        int maxScroll = Math.max(1, lineCount - visible);
        int thumbY = top + Math.round(range * scrollLine / (float) maxScroll);
        graphics.fill(x, top, x + SCROLLBAR_WIDTH, bottom, 0xFF333333);
        graphics.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0xFF9A9A9A);
    }

    private void updateScroll(double mouseY) {
        int maxScroll = maxScroll();
        if (maxScroll == 0) {
            scrollLine = 0;
            return;
        }
        int thumbHeight = thumbHeight(lines().size(), visibleLines());
        int range = Math.max(1, bottom - top - thumbHeight);
        double desiredTop = mouseY - top - thumbHeight / 2D;
        scrollLine = clamp((int) Math.round(desiredTop * maxScroll / range), 0, maxScroll);
    }

    private List<FormattedCharSequence> lines() {
        return font.split(Component.literal(diagnostic.text()), textWidth());
    }

    private int maxScroll() {
        return Math.max(0, lines().size() - visibleLines());
    }

    private int visibleLines() {
        return Math.max(1, (bottom - top) / lineHeight());
    }

    private int lineHeight() {
        return Math.max(1, Math.round(font.lineHeight * SCALE));
    }

    private int thumbHeight(int lineCount, int visibleLines) {
        return Math.max(4, Math.round((bottom - top) * visibleLines / (float) Math.max(1, lineCount)));
    }

    private int textWidth() {
        return Math.max(1, Math.round((scrollBarX() - SCROLLBAR_GAP - left) / SCALE));
    }

    private int scrollBarX() {
        return right - SCROLLBAR_WIDTH;
    }

    private int color() {
        return diagnostic.severity() == NovelDiagnostic.Severity.ERROR ? 0xFFE08080 : 0xFF9CCF9C;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
