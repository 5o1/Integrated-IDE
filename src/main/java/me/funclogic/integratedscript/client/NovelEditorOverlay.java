package me.funclogic.integratedscript.client;

import java.util.List;
import me.funclogic.integratedscript.expr.ExpressionCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.cyclops.integrateddynamics.client.gui.container.ContainerScreenLogicProgrammerBase;
import org.cyclops.integrateddynamics.inventory.container.ContainerLogicProgrammerBase;
import org.lwjgl.glfw.GLFW;

/**
 * Novel mode rendered inside the existing Logic Programmer screen. It owns
 * only the central working area; the original container texture and inventory
 * remain visible and continue to use Integrated Dynamics' normal styling.
 */
final class NovelEditorOverlay {
    // The right-side element configuration area. The left selector/filter and
    // the player inventory deliberately remain outside this rectangle.
    private static final int WORK_X = 88;
    private static final int WORK_Y = 18;
    private static final int WORK_WIDTH = 162;
    private static final int WORK_HEIGHT = 108;
    private static final int STATUS_HEIGHT = 18;
    private static final int EDITOR_PADDING = 4;
    private static final int MAX_COMPLETIONS = 5;
    // This is the original Logic Programmer's write-card slot. Keeping these
    // coordinates makes Novel mode visually continuous with vanilla mode.
    private static final int NATIVE_CARD_SLOT_X = 232;
    private static final int NATIVE_CARD_SLOT_Y = 110;
    private static final int CARD_SLOT_SIZE = 18;

    private final ContainerScreenLogicProgrammerBase<?> screen;
    private final ContainerLogicProgrammerBase menu;
    private final Font font;
    private final int workX;
    private final int workY;
    private final MultiLineEditBox editor;
    private final PanelWidget panel;
    private final ForegroundWidget foreground;
    private final LogicProgrammerCatalog catalog;
    private final boolean completionAvailable;
    private List<LogicProgrammerCatalog.Completion> completions = List.of();
    private ExpressionCompiler.Compilation compilation;
    private CardBuildDriver driver;
    private String status = "\u6309 Ctrl+Enter \u68c0\u67e5\u5e76\u751f\u6210";
    private int selectedCompletion;
    private boolean novelMode;
    private boolean editorFocused;
    private boolean editorDragging;
    private final ModeTabWidget modeTab;

    NovelEditorOverlay(ContainerScreenLogicProgrammerBase<?> screen, ContainerLogicProgrammerBase menu,
                       int guiLeft, int guiTop) {
        this.screen = screen;
        this.menu = menu;
        this.font = screen.getFont();
        this.workX = guiLeft + WORK_X;
        this.workY = guiTop + WORK_Y;
        this.panel = new PanelWidget();
        this.foreground = new ForegroundWidget();
        this.modeTab = new ModeTabWidget(guiLeft + 199, Math.max(0, guiTop - 15));
        this.editor = MultiLineEditBox.builder()
                .setX(workX + EDITOR_PADDING)
                .setY(workY + EDITOR_PADDING)
                .setPlaceholder(Component.translatable("integratedscript.placeholder"))
                .setShowBackground(false)
                .build(font, WORK_WIDTH - EDITOR_PADDING * 2, WORK_HEIGHT - STATUS_HEIGHT - EDITOR_PADDING * 2,
                        Component.translatable("integratedscript.title"));
        this.editor.setCharacterLimit(8_192);
        this.editor.setLineLimit(128);
        this.catalog = LogicProgrammerCatalog.create();
        this.editor.setValueListener(ignored -> sourceChanged());
        this.completionAvailable = CompletionEditorAccess.setCursorListener(editor, this::refreshCompletions);
        if (!completionAvailable) {
            this.status = "\u5f53\u524d Minecraft \u7248\u672c\u65e0\u6cd5\u8bfb\u53d6\u5149\u6807\u4f4d\u7f6e\uff0c\u8865\u5168\u5df2\u5173\u95ed\u3002";
        }
        setNovelMode(false);
    }

    AbstractWidget panel() {
        return panel;
    }

    MultiLineEditBox editor() {
        return editor;
    }

    AbstractWidget foreground() {
        return foreground;
    }

    AbstractWidget modeTab() {
        return modeTab;
    }

    void setNovelMode(boolean enabled) {
        this.novelMode = enabled;
        // Integrated Dynamics renders its element configuration after regular
        // screen widgets. The final Novel layer is therefore rendered from
        // ScreenEvent.Render.Post instead of these earlier widgets.
        this.panel.visible = false;
        this.foreground.visible = false;
        this.editor.visible = enabled;
        this.editor.active = enabled;
        if (enabled) {
            focusEditor();
            sourceChanged();
            refreshCompletions();
        } else {
            yieldEditorFocus();
            this.completions = List.of();
        }
    }

    boolean isNovelMode() {
        return novelMode;
    }

    void renderPost(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (!novelMode) {
            return;
        }
        renderPanel(graphics);
        // The regular widget pass happens before Integrated Dynamics' active
        // element renderer. Extract it again after the opaque panel so the
        // editor is the topmost control in the configuration region.
        editor.extractRenderState(graphics, mouseX, mouseY, partialTick);
        renderForeground(graphics);
    }

    boolean handleKeyPressed(KeyEvent event) {
        if (!novelMode || !editorFocused) {
            return false;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && !completions.isEmpty()) {
            applyCompletion(selectedCompletion);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_DOWN && !completions.isEmpty()) {
            selectedCompletion = (selectedCompletion + 1) % completions.size();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_UP && !completions.isEmpty()) {
            selectedCompletion = (selectedCompletion + completions.size() - 1) % completions.size();
            return true;
        }
        if (event.isConfirmation() && event.hasControlDown()) {
            compileAndBuild();
            return true;
        }
        if (event.isEscape()) {
            setNovelMode(false);
            return true;
        }
        editor.keyPressed(event);
        return true;
    }

    boolean handleCharacterTyped(CharacterEvent event) {
        if (!novelMode || !editorFocused) {
            return false;
        }
        editor.charTyped(event);
        return true;
    }

    boolean handleMousePressed(MouseButtonEvent event, boolean doubleClick) {
        if (!novelMode) {
            return false;
        }
        int completionIndex = completionAt(event.x(), event.y());
        if (completionIndex >= 0) {
            applyCompletion(completionIndex);
            return true;
        }
        if (insideNativeCardSlot(event.x(), event.y())) {
            status = "\u961f\u5217\u4f7f\u7528\u80cc\u5305\u4e2d\u7684\u7a7a\u767d Variable Card\u3002";
            return true;
        }
        if (insideEditor(event.x(), event.y())) {
            focusEditor();
            editor.mouseClicked(event, doubleClick);
            editorDragging = true;
            return true;
        }
        if (insideWorkArea(event.x(), event.y())) {
            return true;
        }
        yieldEditorFocus();
        return false;
    }

    boolean handleMouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!novelMode || !editorDragging) {
            return false;
        }
        editor.mouseDragged(event, dragX, dragY);
        return true;
    }

    boolean handleMouseReleased(MouseButtonEvent event) {
        if (!novelMode || !editorDragging) {
            return false;
        }
        editorDragging = false;
        editor.mouseReleased(event);
        return true;
    }

    boolean handleMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!novelMode || !insideEditor(mouseX, mouseY)) {
            return false;
        }
        editor.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        return true;
    }

    private void focusEditor() {
        editorFocused = true;
        // The parent screen's search field remains a live widget even though
        // Novel mode owns keyboard input. Explicitly defocus it so its caret
        // cannot keep blinking behind the editor.
        if (screen.getSearchField() != null) {
            screen.getSearchField().setFocused(false);
        }
        screen.setFocused(editor);
        editor.setFocused(true);
    }

    private void yieldEditorFocus() {
        editorFocused = false;
        editor.setFocused(false);
        if (screen.getFocused() == editor) {
            screen.clearFocus();
        }
    }

    private void sourceChanged() {
        if (!novelMode || (driver != null && driver.isRunning())) {
            return;
        }
        compilation = catalog.compile(editor.getValue());
        status = validationStatus(compilation);
        refreshCompletions();
    }

    private void tick() {
        if (driver != null) {
            driver.tick();
            status = driver.status();
        }
    }

    private void compileAndBuild() {
        if (driver != null && driver.isRunning()) {
            return;
        }
        compilation = catalog.compile(editor.getValue());
        if (!compilation.valid()) {
            status = compilation.message();
            return;
        }
        RuntimeExpressionValidator.Result runtime = RuntimeExpressionValidator.validate(compilation);
        if (!runtime.valid()) {
            status = runtime.message();
            return;
        }
        int available = CardBuildDriver.countBlankVariableCards(Minecraft.getInstance().player);
        if (available < compilation.steps().size()) {
            status = compilation.message() + "\uff1b\u8fd8\u9700 " + (compilation.steps().size() - available)
                    + " \u5f20\u7a7a\u767d Variable Card\u3002";
            return;
        }
        driver = new CardBuildDriver(menu, compilation);
        driver.start();
    }

    private String validationStatus(ExpressionCompiler.Compilation checked) {
        if (!checked.valid()) {
            return checked.message();
        }
        RuntimeExpressionValidator.Result runtime = RuntimeExpressionValidator.validate(checked);
        return runtime.valid() ? checked.message() + "  " + runtime.message() : runtime.message();
    }

    private void refreshCompletions() {
        if (!novelMode || !completionAvailable) {
            completions = List.of();
            return;
        }
        completions = catalog.completions(editor.getValue(), CompletionEditorAccess.cursor(editor));
        selectedCompletion = Math.min(selectedCompletion, Math.max(0, completions.size() - 1));
    }

    private void applyCompletion(int index) {
        if (index < 0 || index >= completions.size()) {
            return;
        }
        if (!CompletionEditorAccess.replaceCurrentToken(editor, completions.get(index).insertion())) {
            completions = List.of();
            status = "\u5f53\u524d Minecraft \u7248\u672c\u65e0\u6cd5\u8bfb\u53d6\u5149\u6807\u4f4d\u7f6e\uff0c\u8865\u5168\u5df2\u5173\u95ed\u3002";
            return;
        }
        refreshCompletions();
    }

    private void renderForeground(GuiGraphicsExtractor graphics) {
        tick();
        int statusY = workY + WORK_HEIGHT - STATUS_HEIGHT + 4;
        int available = CardBuildDriver.countBlankVariableCards(Minecraft.getInstance().player);
        int required = compilation != null && compilation.valid() ? compilation.steps().size() : 0;
        String queueStatus = "V " + available + "/" + required + "  " + status;
        // The fixed original card slot is deliberately left uncovered. Crop
        // status text before it rather than letting it spill into the slot or
        // the player inventory below.
        String visibleStatus = font.plainSubstrByWidth(queueStatus,
                Math.round((nativeCardSlotX() - workX - 6) / 0.75F));
        graphics.pose().pushMatrix();
        graphics.pose().scale(0.75F, 0.75F);
        graphics.text(font, visibleStatus, Math.round((workX + 5) / 0.75F), Math.round(statusY / 0.75F), statusColor(), false);
        graphics.pose().popMatrix();
        renderErrorUnderline(graphics);
        renderCompletionPopup(graphics);
    }

    private void renderErrorUnderline(GuiGraphicsExtractor graphics) {
        if (compilation == null || compilation.valid() || compilation.errorPosition() < 0) {
            return;
        }
        String source = editor.getValue();
        int position = Math.min(compilation.errorPosition(), source.length());
        int lineStart = source.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        int line = 0;
        for (int index = 0; index < lineStart; index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        int x = editor.getX() + EDITOR_PADDING + font.width(source.substring(lineStart, position));
        int y = editor.getY() + EDITOR_PADDING + line * font.lineHeight - (int) editor.scrollAmount();
        if (y >= editor.getY() && y < editor.getBottom()) {
            graphics.fill(x, y + font.lineHeight - 1, x + 3, y + font.lineHeight, 0xFFE06060);
        }
    }

    private void renderCompletionPopup(GuiGraphicsExtractor graphics) {
        Popup popup = popup();
        if (popup == null) {
            return;
        }
        graphics.fill(popup.x(), popup.y(), popup.x() + popup.width(), popup.y() + popup.height(), 0xF0181818);
        graphics.outline(popup.x(), popup.y(), popup.width(), popup.height(), 0xFF777777);
        int rowY = popup.y() + 3;
        for (PopupRow row : popup.rows()) {
            if (row.completionIndex() == selectedCompletion) {
                graphics.fill(popup.x() + 1, rowY - 1, popup.x() + popup.width() - 1, rowY + row.height() - 1,
                        0xFF4A4A4A);
            }
            int color = row.completionIndex() == selectedCompletion ? 0xFFFFD080 : 0xFFE0E0E0;
            int lineY = rowY;
            for (PopupLine line : row.lines()) {
                int availableWidth = popup.width() - 8 - line.indent();
                String visible = font.plainSubstrByWidth(line.text(), Math.max(1, availableWidth));
                graphics.text(font, visible, popup.x() + 4 + line.indent(), lineY, color, false);
                lineY += completionLineHeight();
            }
            rowY += row.height();
        }
    }

    private Popup popup() {
        if (completions.isEmpty()) {
            return null;
        }
        int width = editor.getWidth();
        int minY = workY + 1;
        int maxY = workY + WORK_HEIGHT - STATUS_HEIGHT - 2;
        CompletionEditorAccess.Caret caret = CompletionEditorAccess.caret(editor);
        String source = editor.getValue();
        int beforeCursor = Math.max(caret.lineStart(), Math.min(caret.cursor(), source.length()));
        int anchorY = editor.getY() + EDITOR_PADDING + caret.visualLine() * font.lineHeight
                - (int) editor.scrollAmount();
        for (int count = Math.min(MAX_COMPLETIONS, completions.size()); count > 0; count--) {
            List<PopupRow> rows = popupRows(count);
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
            return new Popup(editor.getX(), y, width, height, rows);
        }
        return null;
    }

    private int completionAt(double mouseX, double mouseY) {
        Popup popup = popup();
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

    private List<PopupRow> popupRows(int count) {
        List<PopupRow> rows = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<PopupLine> lines = index == 0 ? expandedCompletionLines(completions.get(index))
                    : List.of(new PopupLine(0, collapsedCompletionText(completions.get(index))));
            rows.add(new PopupRow(index, lines, lines.size() * completionLineHeight() + 2));
        }
        return rows;
    }

    private List<PopupLine> expandedCompletionLines(LogicProgrammerCatalog.Completion completion) {
        if (completion.function() == null) {
            return List.of(new PopupLine(0, completion.insertion() + "  " + completion.detail()));
        }
        String opening = completion.insertion();
        List<ExpressionCompiler.TypeInfo> inputs = completion.function().inputTypes();
        int firstArgument = Math.min(completion.receiverArguments(), inputs.size());
        if (firstArgument == inputs.size()) {
            return List.of(new PopupLine(0, opening + ")"));
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
            return List.of(new PopupLine(0, singleLine.toString()));
        }
        List<PopupLine> lines = new java.util.ArrayList<>();
        int indent = font.width(opening);
        for (int index = firstArgument; index < inputs.size(); index++) {
            boolean last = index == inputs.size() - 1;
            String argument = inputs.get(index).displayName() + (last ? ")" : ",");
            lines.add(new PopupLine(index == firstArgument ? 0 : indent,
                    index == firstArgument ? opening + argument : argument));
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

    private int completionLineHeight() {
        return font.lineHeight + 1;
    }

    private int statusColor() {
        if (driver != null && driver.isFailed()) {
            return 0xFFE08080;
        }
        if (compilation != null && !compilation.valid()) {
            return 0xFFE08080;
        }
        return 0xFF9CCF9C;
    }

    private boolean insideEditor(double mouseX, double mouseY) {
        return mouseX >= editor.getX() && mouseX < editor.getRight()
                && mouseY >= editor.getY() && mouseY < editor.getBottom();
    }

    private boolean insideNativeCardSlot(double mouseX, double mouseY) {
        int slotX = nativeCardSlotX();
        int slotY = nativeCardSlotY();
        return mouseX >= slotX && mouseX < slotX + CARD_SLOT_SIZE
                && mouseY >= slotY && mouseY < slotY + CARD_SLOT_SIZE;
    }

    private int nativeCardSlotX() {
        return workX + NATIVE_CARD_SLOT_X - WORK_X;
    }

    private int nativeCardSlotY() {
        return workY + NATIVE_CARD_SLOT_Y - WORK_Y;
    }

    private boolean insideWorkArea(double mouseX, double mouseY) {
        return mouseX >= workX && mouseX < workX + WORK_WIDTH && mouseY >= workY && mouseY < workY + WORK_HEIGHT;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void renderPanel(GuiGraphicsExtractor graphics) {
        int slotX = nativeCardSlotX();
        int slotY = nativeCardSlotY();
        int panelRight = workX + WORK_WIDTH;
        int panelBottom = workY + WORK_HEIGHT;
        // Draw around, rather than over, the native variable-card slot. The
        // slot itself was already rendered by the base container screen.
        graphics.fill(workX, workY, panelRight, slotY, 0xFF161616);
        graphics.fill(workX, slotY, slotX, panelBottom, 0xFF161616);
        graphics.fill(slotX + CARD_SLOT_SIZE, slotY, panelRight, panelBottom, 0xFF161616);
        graphics.fill(slotX, slotY + CARD_SLOT_SIZE, panelRight, panelBottom, 0xFF161616);
        graphics.outline(workX, workY, WORK_WIDTH, WORK_HEIGHT, 0xFF777777);
        graphics.fill(workX + 2, workY + WORK_HEIGHT - STATUS_HEIGHT, slotX - 2,
                workY + WORK_HEIGHT - STATUS_HEIGHT + 1, 0xFF4A4A4A);
    }

    private record Popup(int x, int y, int width, int height, List<PopupRow> rows) {
    }

    private record PopupRow(int completionIndex, List<PopupLine> lines, int height) {
    }

    private record PopupLine(int indent, String text) {
    }

    private final class PanelWidget extends AbstractWidget {
        PanelWidget() {
            super(workX, workY, WORK_WIDTH, WORK_HEIGHT, Component.empty());
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            renderPanel(graphics);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }

    private final class ForegroundWidget extends AbstractWidget {
        ForegroundWidget() {
            super(workX, workY, WORK_WIDTH, WORK_HEIGHT, Component.empty());
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            renderForeground(graphics);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }

    private final class ModeTabWidget extends AbstractWidget {
        ModeTabWidget(int x, int y) {
            super(x, y, 57, 15, Component.literal("Novel"));
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            setNovelMode(!novelMode);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int background = novelMode ? 0xFF303030 : 0xFFB6B6B6;
            int border = novelMode ? 0xFF101010 : 0xFFF0F0F0;
            int foreground = novelMode ? 0xFFE0E0E0 : 0xFF202020;
            graphics.fill(getX(), getY(), getRight(), getBottom(), background);
            graphics.outline(getX(), getY(), getWidth(), getHeight(), border);
            if (!novelMode) {
                graphics.fill(getX() + 1, getBottom() - 1, getRight() - 1, getBottom(), 0xFFB6B6B6);
            }
            graphics.text(font, getMessage(), getX() + 10, getY() + 4, foreground, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
}
