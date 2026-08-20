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
    private static final int STATUS_HEIGHT = 32;
    private static final int EDITOR_PADDING = 4;
    private static final int MAX_SOURCE_CHARACTERS = 8_192;
    private static final int MODE_TAB_WIDTH = 57;
    private static final int MODE_TAB_HEIGHT = 15;
    private static final int MODE_TAB_RIGHT_GUTTER = 6;

    private final ContainerScreenLogicProgrammerBase<?> screen;
    private final ContainerLogicProgrammerBase menu;
    private final Font font;
    private final int workX;
    private final int workY;
    private final int workWidth;
    private final int workHeight;
    private final int nativeCardSlotX;
    private final int nativeCardSlotY;
    private final int cardSlotSize;
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
    private String acceptedSource;
    private boolean buildCommitted;
    private int activeCreatedCards;
    private String rebuildConfirmationSource;
    private final ModeTabWidget modeTab;

    NovelEditorOverlay(ContainerScreenLogicProgrammerBase<?> screen, ContainerLogicProgrammerBase menu,
                       int guiLeft, int guiTop) {
        this.screen = screen;
        this.menu = menu;
        this.font = screen.getFont();
        var writeSlot = LogicProgrammerMenuLayout.writeSlotView(menu);
        this.nativeCardSlotX = guiLeft + writeSlot.x;
        this.nativeCardSlotY = guiTop + writeSlot.y;
        this.cardSlotSize = ContainerScreenLogicProgrammerBase.BOX_HEIGHT;
        // Use the Logic Programmer's published configuration origin and the
        // live write-slot position. This keeps Novel mode aligned if the base
        // screen moves either region instead of embedding a duplicate layout.
        this.workX = guiLeft + ContainerLogicProgrammerBase.BASE_X;
        this.workY = guiTop + ContainerLogicProgrammerBase.BASE_Y;
        this.workWidth = nativeCardSlotX + cardSlotSize - workX;
        this.workHeight = nativeCardSlotY + cardSlotSize - workY;
        this.panel = new PanelWidget();
        this.foreground = new ForegroundWidget();
        this.diagnostics = new NovelDiagnosticPanel(font, workX, workY, workHeight, nativeCardSlotX());
        this.modeTab = new ModeTabWidget(workX + workWidth + MODE_TAB_RIGHT_GUTTER - MODE_TAB_WIDTH,
                Math.max(0, workY - ContainerLogicProgrammerBase.BASE_Y - MODE_TAB_HEIGHT));
        this.editor = MultiLineEditBox.builder()
                .setX(workX + EDITOR_PADDING)
                .setY(workY + EDITOR_PADDING)
                .setPlaceholder(Component.empty())
                .setShowBackground(false)
                .build(font, workWidth - EDITOR_PADDING * 2, workHeight - STATUS_HEIGHT - EDITOR_PADDING * 2,
                        Component.translatable("integratedide.title"));
        this.popup = new NovelCompletionPopup(font, editor, workY + 1,
                workY + workHeight - STATUS_HEIGHT - 2, EDITOR_PADDING, LogicProgrammerCatalog.MAX_COMPLETIONS);
        this.annotations = new NovelEditorAnnotations(font, editor, EDITOR_PADDING, MAX_SOURCE_CHARACTERS,
                nativeCardSlotX(), nativeCardSlotY(), cardSlotSize);
        this.catalog = LogicProgrammerCatalog.create();
        this.session = NovelSessionStore.current();
        this.editor.setValueListener(this::editorValueChanged);
        this.completionAvailable = CompletionEditorAccess.setCursorListener(editor, this::cursorChanged);
        this.acceptedSource = session.source();
        this.editor.setValue(acceptedSource);
        if (!completionAvailable) {
            setError(Component.translatable("integratedide.error.completion_unavailable"));
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
            NovelSessionStore.flush();
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
                setInfo(driver.statusComponent());
            } else if (event.isEscape()) {
                driver.cancel();
                setNovelMode(false);
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
            setInfo(Component.translatable("integratedide.info.blank_card_queue"));
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
            editor.setValue(acceptedSource);
            constrainingSource = false;
            setError(Component.translatable("integratedide.error.source_too_long", MAX_SOURCE_CHARACTERS));
            return;
        }
        acceptedSource = source;
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

    void tick() {
        if (buildRunning()) {
            driver.tick();
            setDiagnostic(driver.isFailed()
                    ? NovelDiagnostic.error(Component.translatable("integratedide.error.build_failed", driver.statusComponent()))
                    : NovelDiagnostic.info(driver.statusComponent()));
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
            setInfo(driver.statusComponent());
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
        NovelSessionStore.flush();
        setInfo(Component.translatable("integratedide.info.build_committed", activeCreatedCards));
    }

    private void compileAndBuildFromCache() {
        var player = Minecraft.getInstance().player;
        activeReconciliation = session.reconcile(compilation);
        boolean forceRebuild = editor.getValue().equals(rebuildConfirmationSource);
        NovelCompilationCache.BuildSelection selection = NovelCompilationCache.select(compilation, activeReconciliation,
                player, forceRebuild);
        if (selection.hasMissingExternal()) {
            NovelCompilationCache.MissingNode missing = selection.missingExternal();
            setError(Component.translatable("integratedide.error.missing_external", missing.variableCardId()));
            return;
        }
        RuntimeExpressionValidator.Result selectedInputValidation = RuntimeExpressionValidator.validateSelectedInputs(
                compilation, selection.availableCards());
        if (!selectedInputValidation.valid()) {
            setDiagnostic(NovelDiagnostic.runtime(compilation, selectedInputValidation));
            return;
        }
        if (selection.needsRebuildConfirmation()) {
            missingCachedNodes = selection.missingCachedNodes();
            rebuildConfirmationSource = editor.getValue();
            setError(Component.translatable("integratedide.error.rebuild_confirmation", missingCachedNodes.size(),
                    IntegratedIdeKeyMappings.COMPILE_NOVEL.getTranslatedKeyMessage()));
            return;
        }
        missingCachedNodes = List.of();
        rebuildConfirmationSource = null;
        int required = selection.stepsToBuild().size();
        int available = CardBuildDriver.countBlankVariableCards(player);
        int freeSlots = CardInventory.countEmptyPlayerSlots(player);
        if (available < required) {
            setError(Component.translatable("integratedide.error.insufficient_blank_cards", required, available));
            return;
        }
        if (freeSlots < required) {
            setError(Component.translatable("integratedide.error.insufficient_inventory_slots", required, freeSlots));
            return;
        }
        if (required == 0) {
            session.commit(compilation, activeReconciliation, selection.availableCards());
            previewReconciliation = session.reconcile(compilation);
            setInfo(Component.translatable("integratedide.info.cache_reused"));
            return;
        }
        driver = new CardBuildDriver(menu, selection.stepsToBuild(), selection.availableCards());
        activeCreatedCards = required;
        buildCommitted = false;
        driver.start();
        editor.active = !buildRunning();
        if (driver.isFailed()) {
            setDiagnostic(NovelDiagnostic.error(
                    Component.translatable("integratedide.error.build_failed", driver.statusComponent())));
        }
        return;
    }

    private void reportValidation(ExpressionCompiler.Compilation checked) {
        if (!checked.valid()) {
            setDiagnostic(NovelDiagnostic.compilation(checked));
            return;
        }
        RuntimeExpressionValidator.Result runtime = RuntimeExpressionValidator.validate(checked);
        if (!completionAvailable) {
            String prefix = runtime.valid() ? checked.message() + "  " + runtime.message() : runtime.message();
            setError(Component.translatable("integratedide.error.completion_unavailable_after", prefix));
            return;
        }
        setDiagnostic(NovelDiagnostic.runtime(checked, runtime));
    }

    private void setInfo(Component message) {
        setDiagnostic(NovelDiagnostic.info(message));
    }

    private void setError(Component message) {
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
            setError(Component.translatable("integratedide.error.completion_unavailable"));
            return;
        }
        refreshCompletions();
    }

    private void renderForeground(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
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
        return mouseX >= slotX && mouseX < slotX + cardSlotSize
                && mouseY >= slotY && mouseY < slotY + cardSlotSize;
    }

    private int nativeCardSlotX() {
        return nativeCardSlotX;
    }

    private int nativeCardSlotY() {
        return nativeCardSlotY;
    }

    private boolean insideWorkArea(double mouseX, double mouseY) {
        return mouseX >= workX && mouseX < workX + workWidth && mouseY >= workY && mouseY < workY + workHeight;
    }

    private void renderPanel(GuiGraphicsExtractor graphics) {
        int slotX = nativeCardSlotX();
        int slotY = nativeCardSlotY();
        int panelRight = workX + workWidth;
        int panelBottom = workY + workHeight;
        // Draw around, rather than over, the native variable-card slot. The
        // slot itself was already rendered by the base container screen.
        graphics.fill(workX, workY, panelRight, slotY, 0xFF161616);
        graphics.fill(workX, slotY, slotX, panelBottom, 0xFF161616);
        graphics.fill(slotX + cardSlotSize, slotY, panelRight, panelBottom, 0xFF161616);
        graphics.fill(slotX, slotY + cardSlotSize, panelRight, panelBottom, 0xFF161616);
        graphics.outline(workX, workY, workWidth, workHeight, 0xFF777777);
        diagnostics.renderBackground(graphics);
        annotations.renderCounterBackground(graphics);
        // Draw the label before the editor's widget render. Its dark backing
        // and text are deliberately behind user input, so typing in the lower
        // right corner remains completely readable.
        annotations.renderCounter(graphics);
    }

    private final class PanelWidget extends AbstractWidget {
        PanelWidget() {
            super(workX, workY, workWidth, workHeight, Component.empty());
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
            super(workX, workY, workWidth, workHeight, Component.empty());
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
            super(x, y, MODE_TAB_WIDTH, MODE_TAB_HEIGHT, Component.translatable("integratedide.mode.novel"));
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (buildRunning()) {
                driver.cancel();
                setNovelMode(false);
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
