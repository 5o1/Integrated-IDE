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
    private static final int STATUS_HEIGHT = 32;
    private static final int EDITOR_PADDING = 4;
    private static final int MAX_SOURCE_CHARACTERS = 8_192;
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
    private final NovelDiagnosticPanel diagnostics;
    private final NovelCompletionPopup popup;
    private final NovelEditorAnnotations annotations;
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
    private int selectedCompletion;
    private NovelCompletionPopup.Mode popupMode = NovelCompletionPopup.Mode.NONE;
    private boolean novelMode;
    private boolean editorFocused;
    private boolean editorDragging;
    private boolean statusDragging;
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
        this.diagnostics = new NovelDiagnosticPanel(font, workX, workY, WORK_HEIGHT, nativeCardSlotX());
        this.modeTab = new ModeTabWidget(guiLeft + 199, Math.max(0, guiTop - 15));
        this.editor = MultiLineEditBox.builder()
                .setX(workX + EDITOR_PADDING)
                .setY(workY + EDITOR_PADDING)
                .setPlaceholder(Component.empty())
                .setShowBackground(false)
                .build(font, WORK_WIDTH - EDITOR_PADDING * 2, WORK_HEIGHT - STATUS_HEIGHT - EDITOR_PADDING * 2,
                        Component.translatable("integratedide.title"));
        this.editor.setLineLimit(128);
        this.popup = new NovelCompletionPopup(font, editor, workY + 1,
                workY + WORK_HEIGHT - STATUS_HEIGHT - 2, EDITOR_PADDING, 5);
        this.annotations = new NovelEditorAnnotations(font, editor, EDITOR_PADDING, MAX_SOURCE_CHARACTERS,
                nativeCardSlotX(), nativeCardSlotY(), CARD_SLOT_SIZE);
        this.catalog = LogicProgrammerCatalog.create();
        this.session = NovelSessionStore.current();
        this.editor.setValueListener(this::editorValueChanged);
        this.completionAvailable = CompletionEditorAccess.setCursorListener(editor, this::cursorChanged);
        this.editor.setValue(session.source());
        if (!completionAvailable) {
            setError("\u5f53\u524d Minecraft \u7248\u672c\u65e0\u6cd5\u8bfb\u53d6\u5149\u6807\u4f4d\u7f6e\uff0c\u8865\u5168\u5df2\u5173\u95ed\u3002");
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
        finalizeCompletedBuild();
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
        this.editor.active = enabled && !buildRunning();
        if (enabled) {
            focusEditor();
            sourceChanged();
        } else {
            yieldEditorFocus();
            this.completions = List.of();
            this.signature = null;
            this.popupMode = NovelCompletionPopup.Mode.NONE;
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
        if (buildRunning()) {
            if (IntegratedIdeKeyMappings.matchesCompile(event)) {
                setInfo("\u6b63\u5728\u751f\u6210\u53d8\u91cf\u5361\uff1a" + driver.status());
            } else if (event.isEscape()) {
                setInfo("\u6b63\u5728\u751f\u6210\u53d8\u91cf\u5361\uff0c\u65e0\u6cd5\u5173\u95ed Novel \u6a21\u5f0f\u3002");
            }
            return true;
        }
        if (IntegratedIdeKeyMappings.matchesCompletion(event)) {
            completionExplicitlyRequested = true;
            refreshCompletions();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && popupMode == NovelCompletionPopup.Mode.COMPLETIONS
                && !completions.isEmpty()) {
            applyCompletion(selectedCompletion);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_DOWN && popupMode == NovelCompletionPopup.Mode.COMPLETIONS
                && !completions.isEmpty()) {
            selectedCompletion = (selectedCompletion + 1) % completions.size();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_UP && popupMode == NovelCompletionPopup.Mode.COMPLETIONS
                && !completions.isEmpty()) {
            selectedCompletion = (selectedCompletion + completions.size() - 1) % completions.size();
            return true;
        }
        if (IntegratedIdeKeyMappings.matchesCompile(event)) {
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
        if (buildRunning()) {
            return true;
        }
        editor.charTyped(event);
        return true;
    }

    boolean handleMousePressed(MouseButtonEvent event, boolean doubleClick) {
        if (!novelMode) {
            return false;
        }
        int completionIndex = popup.completionAt(popupMode, completions, signature, event.x(), event.y());
        if (completionIndex >= 0) {
            applyCompletion(completionIndex);
            return true;
        }
        if (insideNativeCardSlot(event.x(), event.y())) {
            setInfo("\u961f\u5217\u4f7f\u7528\u80cc\u5305\u4e2d\u7684\u7a7a\u767d Variable Card\u3002");
            return true;
        }
        if (diagnostics.contains(event.x(), event.y())) {
            statusDragging = diagnostics.beginScrollDrag(event.x(), event.y());
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
        if (!novelMode) {
            return false;
        }
        if (statusDragging) {
            diagnostics.dragTo(event.y());
            return true;
        }
        if (!editorDragging) {
            return false;
        }
        editor.mouseDragged(event, dragX, dragY);
        return true;
    }

    boolean handleMouseReleased(MouseButtonEvent event) {
        if (!novelMode) {
            return false;
        }
        if (statusDragging) {
            statusDragging = false;
            return true;
        }
        if (!editorDragging) {
            return false;
        }
        editorDragging = false;
        editor.mouseReleased(event);
        return true;
    }

    boolean handleMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!novelMode) {
            return false;
        }
        if (diagnostics.contains(mouseX, mouseY)) {
            diagnostics.scroll(scrollY);
            return true;
        }
        if (!insideEditor(mouseX, mouseY)) {
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
        if (!novelMode || buildRunning()) {
            return;
        }
        completionExplicitlyRequested = false;
        compilation = catalog.compile(editor.getValue());
        previewReconciliation = compilation.valid() ? session.reconcile(compilation) : null;
        reportValidation(compilation);
        refreshCompletions();
    }

    private void cursorChanged() {
        completionExplicitlyRequested = false;
        refreshCompletions();
    }

    private void tick() {
        NovelSessionStore.flushIfDue();
        if (buildRunning()) {
            driver.tick();
            setDiagnostic(driver.isFailed() ? NovelDiagnostic.error("\u6784\u5efa\u5931\u8d25\n" + driver.status())
                    : NovelDiagnostic.info(driver.status()));
            if (!buildRunning()) {
                editor.active = novelMode;
            }
        }
        finalizeCompletedBuild();
    }

    private void compileAndBuild() {
        // A build can finish between screen frames, or just before the user
        // presses the shortcut again. Commit it before recompiling so the
        // second invocation can reuse its Variable Cards.
        finalizeCompletedBuild();
        if (buildRunning()) {
            setInfo("\u6b63\u5728\u751f\u6210\u53d8\u91cf\u5361\uff1a" + driver.status());
            return;
        }
        compilation = catalog.compile(editor.getValue());
        previewReconciliation = compilation.valid() ? session.reconcile(compilation) : null;
        if (!compilation.valid()) {
            setDiagnostic(NovelDiagnostic.compilation(compilation));
            return;
        }
        RuntimeExpressionValidator.Result runtime = RuntimeExpressionValidator.validate(compilation);
        if (!runtime.valid()) {
            setDiagnostic(NovelDiagnostic.runtime(compilation, runtime));
            return;
        }
        compileAndBuildFromCache();
    }

    private void finalizeCompletedBuild() {
        if (driver == null || !driver.isComplete() || buildCommitted || compilation == null || activeReconciliation == null) {
            return;
        }
        session.commit(compilation, activeReconciliation, driver.producedCards());
        previewReconciliation = session.reconcile(compilation);
        buildCommitted = true;
        setInfo("\u5b8c\u6210\uff1a\u5df2\u521b\u5efa " + activeCreatedCards + " \u5f20\u53d8\u91cf\u5361\u3002");
    }

    private void compileAndBuildFromCache() {
        var player = Minecraft.getInstance().player;
        activeReconciliation = session.reconcile(compilation);
        boolean forceRebuild = editor.getValue().equals(rebuildConfirmationSource);
        NovelCompilationCache.BuildSelection selection = NovelCompilationCache.select(compilation, activeReconciliation,
                player, forceRebuild);
        if (selection.hasMissingExternal()) {
            NovelCompilationCache.MissingNode missing = selection.missingExternal();
            setError("构建失败\n外部变量卡 {" + missing.variableCardId() + "} 不在背包中，或类型不符合当前参数。");
            return;
        }
        if (selection.needsRebuildConfirmation()) {
            missingCachedNodes = selection.missingCachedNodes();
            rebuildConfirmationSource = editor.getValue();
            setError("构建需要确认\n缺少 " + missingCachedNodes.size()
                    + " 个缓存变量卡；再次按 Ctrl+Enter 将重编译它们及其依赖者。");
            return;
        }
        missingCachedNodes = List.of();
        rebuildConfirmationSource = null;
        int required = selection.stepsToBuild().size();
        int available = CardBuildDriver.countBlankVariableCards(player);
        int freeSlots = CardInventory.countEmptyPlayerSlots(player);
        if (available < required) {
            setError("构建失败\n空白 Variable Card 不足：需要 " + required + "，背包中有 " + available + "。");
            return;
        }
        if (freeSlots < required) {
            setError("构建失败\n背包空槽不足：需要 " + required + "，剩余 " + freeSlots + "。");
            return;
        }
        if (required == 0) {
            session.commit(compilation, activeReconciliation, selection.availableCards());
            previewReconciliation = session.reconcile(compilation);
            setInfo("无需新建变量卡，已复用缓存图。");
            return;
        }
        driver = new CardBuildDriver(menu, selection.stepsToBuild(), selection.availableCards());
        activeCreatedCards = required;
        buildCommitted = false;
        driver.start();
        editor.active = !buildRunning();
        if (driver.isFailed()) {
            setDiagnostic(NovelDiagnostic.error("\u6784\u5efa\u5931\u8d25\n" + driver.status()));
        }
        return;
    }

    private void reportValidation(ExpressionCompiler.Compilation checked) {
        if (!checked.valid()) {
            setDiagnostic(NovelDiagnostic.compilation(checked));
            return;
        }
        setDiagnostic(NovelDiagnostic.runtime(checked, RuntimeExpressionValidator.validate(checked)));
    }

    private void setInfo(String message) {
        setDiagnostic(NovelDiagnostic.info(message));
    }

    private void setError(String message) {
        setDiagnostic(NovelDiagnostic.error(message));
    }

    /**
     * The panel owns its scroll state and resets it for each replacement, so
     * a new compiler error is never hidden by an old scroll position.
     */
    private void setDiagnostic(NovelDiagnostic next) {
        diagnostics.setDiagnostic(next);
    }

    private boolean buildRunning() {
        return driver != null && driver.isRunning();
    }

    private void refreshCompletions() {
        if (!novelMode || !completionAvailable) {
            completions = List.of();
            signature = null;
            popupMode = NovelCompletionPopup.Mode.NONE;
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
            popupMode = NovelCompletionPopup.Mode.COMPLETIONS;
            return;
        }
        completions = List.of();
        popupMode = signature != null && signature.emptyArgument() ? NovelCompletionPopup.Mode.SIGNATURE
                : NovelCompletionPopup.Mode.NONE;
    }

    private void applyCompletion(int index) {
        if (index < 0 || index >= completions.size()) {
            return;
        }
        if (!CompletionEditorAccess.replaceCurrentToken(editor, completions.get(index).insertion())) {
            completions = List.of();
            popupMode = NovelCompletionPopup.Mode.NONE;
            setError("\u5f53\u524d Minecraft \u7248\u672c\u65e0\u6cd5\u8bfb\u53d6\u5149\u6807\u4f4d\u7f6e\uff0c\u8865\u5168\u5df2\u5173\u95ed\u3002");
            return;
        }
        refreshCompletions();
    }

    private void renderForeground(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        tick();
        diagnostics.render(graphics);
        annotations.render(graphics, compilation, previewReconciliation, missingCachedNodes,
                requiredBlankCards(Minecraft.getInstance().player), mouseX, mouseY);
        popup.render(graphics, popupMode, completions, signature, selectedCompletion);
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
        diagnostics.renderBackground(graphics);
        annotations.renderCounterBackground(graphics);
        // Draw the label before the editor's widget render. Its dark backing
        // and text are deliberately behind user input, so typing in the lower
        // right corner remains completely readable.
        annotations.renderCounter(graphics);
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
            if (buildRunning()) {
                setInfo("\u6b63\u5728\u751f\u6210\u53d8\u91cf\u5361\uff0c\u6682\u65f6\u4e0d\u80fd\u5207\u6362\u6a21\u5f0f\u3002");
                return;
            }
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
