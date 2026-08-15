package me.funclogic.integratedide.client;

import java.util.List;
import me.funclogic.integratedide.expr.ExpressionCompiler;
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
import net.minecraft.util.FormattedCharSequence;
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
    private static final int MAX_SOURCE_CHARACTERS = 8_192;
    private static final int EMPTY_GUIDE_LINES = 8;
    private static final float GUIDE_SCALE = 0.75F;
    private static final int GUIDE_LINE_HEIGHT = 7;
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
    private final NovelSessionStore.Session session;
    private final boolean completionAvailable;
    private List<LogicProgrammerCatalog.Completion> completions = List.of();
    private LogicProgrammerCatalog.Signature signature;
    private ExpressionCompiler.Compilation compilation;
    private NovelCompilationCache.Reconciliation previewReconciliation;
    private CardBuildDriver driver;
    private NovelCompilationCache.Reconciliation activeReconciliation;
    private List<NovelCompilationCache.MissingNode> missingCachedNodes = List.of();
    private String status = "\u6309 Ctrl+Enter \u68c0\u67e5\u5e76\u751f\u6210";
    private int selectedCompletion;
    private PopupMode popupMode = PopupMode.NONE;
    private boolean novelMode;
    private boolean editorFocused;
    private boolean editorDragging;
    private boolean completionExplicitlyRequested;
    private boolean constrainingSource;
    private boolean buildCommitted;
    private int activeCreatedCards;
    private String rebuildConfirmationSource;
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
                .setPlaceholder(Component.empty())
                .setShowBackground(false)
                .build(font, WORK_WIDTH - EDITOR_PADDING * 2, WORK_HEIGHT - STATUS_HEIGHT - EDITOR_PADDING * 2,
                        Component.translatable("integratedide.title"));
        this.editor.setLineLimit(128);
        this.catalog = LogicProgrammerCatalog.create();
        this.session = NovelSessionStore.current();
        this.editor.setValueListener(this::editorValueChanged);
        this.completionAvailable = CompletionEditorAccess.setCursorListener(editor, this::cursorChanged);
        this.editor.setValue(session.source());
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

    void close() {
        NovelSessionStore.flush();
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
        } else {
            yieldEditorFocus();
            this.completions = List.of();
            this.signature = null;
            this.popupMode = PopupMode.NONE;
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
        renderForeground(graphics, mouseX, mouseY);
    }

    boolean handleKeyPressed(KeyEvent event) {
        if (!novelMode || !editorFocused) {
            return false;
        }
        if (IntegratedIdeKeyMappings.REQUEST_COMPLETION.matches(event)) {
            completionExplicitlyRequested = true;
            refreshCompletions();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && popupMode == PopupMode.COMPLETIONS && !completions.isEmpty()) {
            applyCompletion(selectedCompletion);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_DOWN && popupMode == PopupMode.COMPLETIONS && !completions.isEmpty()) {
            selectedCompletion = (selectedCompletion + 1) % completions.size();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_UP && popupMode == PopupMode.COMPLETIONS && !completions.isEmpty()) {
            selectedCompletion = (selectedCompletion + completions.size() - 1) % completions.size();
            return true;
        }
        if (IntegratedIdeKeyMappings.COMPILE_NOVEL.matches(event)) {
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

    private void editorValueChanged(String source) {
        if (constrainingSource) {
            return;
        }
        if (source.length() > MAX_SOURCE_CHARACTERS) {
            constrainingSource = true;
            editor.setValue(source.substring(0, MAX_SOURCE_CHARACTERS));
            constrainingSource = false;
            source = editor.getValue();
        }
        session.setSource(source);
        rebuildConfirmationSource = null;
        missingCachedNodes = List.of();
        sourceChanged();
    }

    private void sourceChanged() {
        if (!novelMode || (driver != null && driver.isRunning())) {
            return;
        }
        completionExplicitlyRequested = false;
        compilation = catalog.compile(editor.getValue());
        previewReconciliation = compilation.valid() ? session.reconcile(compilation) : null;
        status = validationStatus(compilation);
        refreshCompletions();
    }

    private void cursorChanged() {
        completionExplicitlyRequested = false;
        refreshCompletions();
    }

    private void tick() {
        NovelSessionStore.flushIfDue();
        if (driver != null && driver.isRunning()) {
            driver.tick();
            status = driver.status();
        }
        if (driver != null && driver.isComplete() && !buildCommitted) {
            session.commit(compilation, activeReconciliation, driver.producedCards());
            previewReconciliation = session.reconcile(compilation);
            buildCommitted = true;
            status = "\u5b8c\u6210\uff1a\u5df2\u521b\u5efa " + activeCreatedCards + " \u5f20\u53d8\u91cf\u5361\u3002";
        }
    }

    private void compileAndBuild() {
        if (driver != null && driver.isRunning()) {
            return;
        }
        compilation = catalog.compile(editor.getValue());
        previewReconciliation = compilation.valid() ? session.reconcile(compilation) : null;
        if (!compilation.valid()) {
            status = compilation.message();
            return;
        }
        RuntimeExpressionValidator.Result runtime = RuntimeExpressionValidator.validate(compilation);
        if (!runtime.valid()) {
            status = runtime.message();
            return;
        }
        compileAndBuildFromCache();
    }

    private void compileAndBuildFromCache() {
        var player = Minecraft.getInstance().player;
        activeReconciliation = session.reconcile(compilation);
        boolean forceRebuild = editor.getValue().equals(rebuildConfirmationSource);
        NovelCompilationCache.BuildSelection selection = NovelCompilationCache.select(compilation, activeReconciliation,
                player, forceRebuild);
        if (selection.hasMissingExternal()) {
            NovelCompilationCache.MissingNode missing = selection.missingExternal();
            status = "外部变量卡 {" + missing.variableCardId() + "} 不在背包中，或类型不符合当前参数。";
            return;
        }
        if (selection.needsRebuildConfirmation()) {
            missingCachedNodes = selection.missingCachedNodes();
            rebuildConfirmationSource = editor.getValue();
            status = "缺少 " + missingCachedNodes.size() + " 个缓存变量卡；再次按 Ctrl+Enter 将重编译它们及其依赖者。";
            return;
        }
        missingCachedNodes = List.of();
        rebuildConfirmationSource = null;
        int required = selection.stepsToBuild().size();
        int available = CardBuildDriver.countBlankVariableCards(player);
        int freeSlots = CardInventory.countEmptyPlayerSlots(player);
        if (available < required) {
            status = "空白 Variable Card 不足：需要 " + required + "，背包中有 " + available + "。";
            return;
        }
        if (freeSlots < required) {
            status = "背包空槽不足：需要 " + required + "，剩余 " + freeSlots + "。";
            return;
        }
        if (required == 0) {
            session.commit(compilation, activeReconciliation, selection.availableCards());
            previewReconciliation = session.reconcile(compilation);
            status = "无需新建变量卡，已复用缓存图。";
            return;
        }
        driver = new CardBuildDriver(menu, selection.stepsToBuild(), selection.availableCards());
        activeCreatedCards = required;
        buildCommitted = false;
        driver.start();
        return;
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
            signature = null;
            popupMode = PopupMode.NONE;
            return;
        }
        String source = editor.getValue();
        int cursor = CompletionEditorAccess.cursor(editor);
        signature = catalog.signatureAt(source, cursor);
        boolean automatic = catalog.hasAutomaticCompletionTrigger(source, cursor);
        if (completionExplicitlyRequested || automatic) {
            ExpressionCompiler.TypeInfo expectedType = signature == null ? null : signature.expectedType();
            completions = catalog.completions(source, cursor, expectedType, completionExplicitlyRequested);
            selectedCompletion = Math.min(selectedCompletion, Math.max(0, completions.size() - 1));
            popupMode = PopupMode.COMPLETIONS;
            return;
        }
        completions = List.of();
        popupMode = signature != null && signature.emptyArgument() ? PopupMode.SIGNATURE : PopupMode.NONE;
    }

    private void applyCompletion(int index) {
        if (index < 0 || index >= completions.size()) {
            return;
        }
        if (!CompletionEditorAccess.replaceCurrentToken(editor, completions.get(index).insertion())) {
            completions = List.of();
            popupMode = PopupMode.NONE;
            status = "\u5f53\u524d Minecraft \u7248\u672c\u65e0\u6cd5\u8bfb\u53d6\u5149\u6807\u4f4d\u7f6e\uff0c\u8865\u5168\u5df2\u5173\u95ed\u3002";
            return;
        }
        refreshCompletions();
    }

    private void renderForeground(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        tick();
        int statusY = workY + WORK_HEIGHT - STATUS_HEIGHT + 4;
        // The fixed original card slot is deliberately left uncovered. Crop
        // status text before it rather than letting it spill into the slot or
        // the player inventory below.
        String visibleStatus = font.plainSubstrByWidth(status,
                Math.round((nativeCardSlotX() - workX - 6) / 0.75F));
        graphics.pose().pushMatrix();
        graphics.pose().scale(0.75F, 0.75F);
        graphics.text(font, visibleStatus, Math.round((workX + 5) / 0.75F), Math.round(statusY / 0.75F), statusColor(), false);
        graphics.pose().popMatrix();
        renderCardCapacity(graphics);
        renderEmptyEditorGuide(graphics);
        renderExternalReferences(graphics);
        renderMissingCachedNodeMarkers(graphics);
        renderErrorUnderline(graphics);
        renderHoveredRootId(graphics, mouseX, mouseY);
        renderPopup(graphics);
    }

    private void renderEmptyEditorGuide(GuiGraphicsExtractor graphics) {
        if (!editor.getValue().isEmpty()) {
            return;
        }
        int x = editor.getX() + EDITOR_PADDING;
        int y = editor.getY() + EDITOR_PADDING;
        int maxWidth = Math.round((editor.getWidth() - EDITOR_PADDING * 2) / GUIDE_SCALE);
        int visualLine = 0;
        graphics.pose().pushMatrix();
        graphics.pose().scale(GUIDE_SCALE, GUIDE_SCALE);
        for (int line = 1; line <= EMPTY_GUIDE_LINES; line++) {
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

    private void renderHoveredRootId(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!insideEditor(mouseX, mouseY) || compilation == null || !compilation.valid()) {
            return;
        }
        String source = editor.getValue();
        int relativeY = mouseY - editor.getY() - EDITOR_PADDING + (int) editor.scrollAmount();
        if (relativeY < 0) {
            return;
        }
        int line = relativeY / font.lineHeight;
        int lineStart = sourceLineStart(source, line);
        if (lineStart < 0) {
            return;
        }
        int newline = source.indexOf('\n', lineStart);
        int lineEnd = newline < 0 ? source.length() : newline;
        ExpressionCompiler.StatementRoot root = compilation.statementRoots().stream()
                .filter(candidate -> candidate.sourceStart() >= lineStart && candidate.sourceStart() < lineEnd)
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
            boolean available = CardInventory.findVariableCardById(Minecraft.getInstance().player, id,
                    rootStep.outputTypeId()) != null;
            color = available ? 0xFF55AAFF : 0xFFFF5555;
        } else {
            NovelCompilationCache.CachedNode cached = previewReconciliation == null ? null
                    : previewReconciliation.match(root.stepId());
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

    private static int sourceLineStart(String source, int line) {
        int start = 0;
        for (int current = 0; current < line; current++) {
            int newline = source.indexOf('\n', start);
            if (newline < 0) {
                return -1;
            }
            start = newline + 1;
        }
        return start;
    }

    private void renderCardCapacity(GuiGraphicsExtractor graphics) {
        var player = Minecraft.getInstance().player;
        int required = requiredBlankCards(player);
        int available = CardBuildDriver.countBlankVariableCards(player);
        int freeSlots = CardInventory.countEmptyPlayerSlots(player);
        String capacity = required + "/" + available + "/" + freeSlots;
        int left = nativeCardSlotX() + 1;
        int right = nativeCardSlotX() + CARD_SLOT_SIZE - 1;
        int top = nativeCardSlotY() + 1;
        int bottom = nativeCardSlotY() + CARD_SLOT_SIZE - 1;
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

    private int requiredBlankCards(net.minecraft.world.entity.player.Player player) {
        if (compilation == null || !compilation.valid()) {
            return 0;
        }
        NovelCompilationCache.BuildSelection selection = NovelCompilationCache.select(compilation,
                session.reconcile(compilation), player, true);
        if (!selection.hasMissingExternal()) {
            return selection.stepsToBuild().size();
        }
        return (int) compilation.steps().stream().filter(ExpressionCompiler.CardStep::createsVariableCard).count();
    }

    private void renderEditorCharacterCount(GuiGraphicsExtractor graphics) {
        String counter = editor.getValue().length() + "/" + MAX_SOURCE_CHARACTERS;
        int x = editor.getRight() - font.width(counter) - 4;
        int y = editor.getBottom() - font.lineHeight - 3;
        graphics.text(font, counter, x, y, 0xFFA0A0A0, false);
    }

    private void renderExternalReferences(GuiGraphicsExtractor graphics) {
        if (compilation == null || !compilation.valid()) {
            return;
        }
        var player = Minecraft.getInstance().player;
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

    private int explicitReferenceEnd(ExpressionCompiler.CardStep step) {
        String source = editor.getValue();
        int closingBrace = source.indexOf('}', Math.max(0, step.sourceStart()));
        return closingBrace < 0 ? step.sourceEnd() : closingBrace + 1;
    }

    private void renderMissingCachedNodeMarkers(GuiGraphicsExtractor graphics) {
        for (NovelCompilationCache.MissingNode missing : missingCachedNodes) {
            renderRangeUnderline(graphics, missing.step().sourceStart(), missing.step().sourceEnd(), 0xFFE06060);
        }
    }

    private void renderTextRange(GuiGraphicsExtractor graphics, int start, int end, int color) {
        TextLocation location = textLocation(start);
        String source = editor.getValue();
        int safeEnd = Math.max(location.position(), Math.min(end, source.length()));
        int lineEnd = source.indexOf('\n', location.position());
        if (lineEnd < 0) {
            lineEnd = source.length();
        }
        if (location.y() >= editor.getY() && location.y() < editor.getBottom()
                && lineEnd >= safeEnd) {
            graphics.text(font, source.substring(location.position(), safeEnd), location.x(), location.y(), color, false);
        }
    }

    private void renderRangeUnderline(GuiGraphicsExtractor graphics, int start, int end, int color) {
        TextLocation location = textLocation(start);
        String source = editor.getValue();
        int safeEnd = Math.max(location.position(), Math.min(end, source.length()));
        int lineEnd = source.indexOf('\n', location.position());
        if (lineEnd < 0) {
            lineEnd = source.length();
        }
        if (location.y() >= editor.getY() && location.y() < editor.getBottom()) {
            int width = font.width(source.substring(location.position(), Math.min(safeEnd, lineEnd)));
            graphics.fill(location.x(), location.y() + font.lineHeight - 1, location.x() + Math.max(3, width),
                    location.y() + font.lineHeight, color);
        }
    }

    private TextLocation textLocation(int position) {
        String source = editor.getValue();
        int safePosition = Math.max(0, Math.min(position, source.length()));
        int lineStart = source.lastIndexOf('\n', safePosition - 1) + 1;
        int line = 0;
        for (int index = 0; index < lineStart; index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        int x = editor.getX() + EDITOR_PADDING + font.width(source.substring(lineStart, safePosition));
        int y = editor.getY() + EDITOR_PADDING + line * font.lineHeight - (int) editor.scrollAmount();
        return new TextLocation(safePosition, x, y);
    }

    private void renderErrorUnderline(GuiGraphicsExtractor graphics) {
        if (compilation == null || compilation.valid() || compilation.errorPosition() < 0) {
            return;
        }
        String source = editor.getValue();
        int position = Math.min(compilation.errorPosition(), source.length());
        int lineStart = source.lastIndexOf('\n', position - 1) + 1;
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

    private void renderPopup(GuiGraphicsExtractor graphics) {
        Popup popup = popup();
        if (popup == null) {
            return;
        }
        graphics.fill(popup.x(), popup.y(), popup.x() + popup.width(), popup.y() + popup.height(), 0xF0181818);
        graphics.outline(popup.x(), popup.y(), popup.width(), popup.height(), 0xFF777777);
        int rowY = popup.y() + 3;
        for (PopupRow row : popup.rows()) {
            boolean selected = popupMode == PopupMode.COMPLETIONS && row.completionIndex() == selectedCompletion;
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
                lineY += completionLineHeight();
            }
            rowY += row.height();
        }
    }

    private Popup popup() {
        if (popupMode == PopupMode.NONE || popupMode == PopupMode.COMPLETIONS && completions.isEmpty()) {
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
        int maximumRows = popupMode == PopupMode.COMPLETIONS ? Math.min(MAX_COMPLETIONS, completions.size()) : 1;
        for (int count = maximumRows; count > 0; count--) {
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
        if (popupMode == PopupMode.SIGNATURE && signature != null) {
            List<PopupLine> lines = signatureLines(signature);
            return List.of(new PopupRow(-1, lines, lines.size() * completionLineHeight() + 2));
        }
        List<PopupRow> rows = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<PopupLine> lines = index == 0 ? expandedCompletionLines(completions.get(index))
                    : List.of(new PopupLine(0, collapsedCompletionText(completions.get(index)), false));
            rows.add(new PopupRow(index, lines, lines.size() * completionLineHeight() + 2));
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
        List<PopupLine> lines = new java.util.ArrayList<>();
        int indent = font.width(opening);
        for (int index = firstArgument; index < inputs.size(); index++) {
            boolean last = index == inputs.size() - 1;
            String argument = inputs.get(index).displayName() + (last ? ")" : ",");
            lines.add(new PopupLine(index == firstArgument ? 0 : indent,
                    index == firstArgument ? opening + argument : argument, false));
        }
        return lines;
    }

    private List<PopupLine> signatureLines(LogicProgrammerCatalog.Signature current) {
        List<ExpressionCompiler.TypeInfo> inputs = current.function().inputTypes();
        int firstArgument = Math.min(current.receiverArguments(), inputs.size());
        String opening = current.invocation() + "(";
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
        List<PopupLine> lines = new java.util.ArrayList<>();
        int indent = font.width(opening);
        int activeInput = firstArgument + current.activeArgument();
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
        if (!missingCachedNodes.isEmpty()) {
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
        renderEditorCharacterCountBackground(graphics);
        // Draw the label before the editor's widget render. Its dark backing
        // and text are deliberately behind user input, so typing in the lower
        // right corner remains completely readable.
        renderEditorCharacterCount(graphics);
    }

    private void renderEditorCharacterCountBackground(GuiGraphicsExtractor graphics) {
        String counter = editor.getValue().length() + "/" + MAX_SOURCE_CHARACTERS;
        int right = editor.getRight() - 2;
        int bottom = editor.getBottom() - 2;
        int x = right - font.width(counter) - 4;
        int y = bottom - font.lineHeight - 2;
        graphics.fill(x - 2, y - 1, right, bottom, 0xD0101010);
    }

    private record Popup(int x, int y, int width, int height, List<PopupRow> rows) {
    }

    private record TextLocation(int position, int x, int y) {
    }

    private record PopupRow(int completionIndex, List<PopupLine> lines, int height) {
    }

    private enum PopupMode {
        NONE, SIGNATURE, COMPLETIONS
    }

    private record PopupLine(int indent, String text, boolean active) {
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
            renderForeground(graphics, mouseX, mouseY);
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
