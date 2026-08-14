package me.funclogic.integratedscript.client;

import com.mojang.logging.LogUtils;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.cyclops.integrateddynamics.client.gui.container.ContainerScreenLogicProgrammerBase;
import org.cyclops.integrateddynamics.inventory.container.ContainerLogicProgrammerBase;
import org.slf4j.Logger;

/** Adds an in-place Novel mode to the normal Logic Programmer screen. */
public final class LogicProgrammerScreenHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Screen, NovelEditorOverlay> OVERLAYS = Collections.synchronizedMap(new WeakHashMap<>());

    private LogicProgrammerScreenHooks() {
    }

    @SuppressWarnings("removal") // NeoForge 1.21.1 exposes no non-deprecated replacement yet.
    public static void addNovelModeControls(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ContainerScreenLogicProgrammerBase<?> programmer)
                || !(programmer.getMenu() instanceof ContainerLogicProgrammerBase menu)) {
            return;
        }
        int guiLeft = programmer.getGuiLeft();
        int guiTop = programmer.getGuiTop();
        NovelEditorOverlay overlay = new NovelEditorOverlay(programmer, menu, guiLeft, guiTop);
        OVERLAYS.put(programmer, overlay);

        event.addListener(overlay.panel());
        event.addListener(overlay.editor());
        event.addListener(overlay.foreground());
        event.addListener(overlay.modeTab());
        LOGGER.info("Attached embedded Novel mode to {}", programmer.getClass().getName());
    }

    public static void handleKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        NovelEditorOverlay overlay = OVERLAYS.get(event.getScreen());
        if (overlay != null && overlay.handleKeyPressed(event.getKeyEvent())) {
            event.setCanceled(true);
        }
    }

    public static void handleCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
        NovelEditorOverlay overlay = OVERLAYS.get(event.getScreen());
        if (overlay != null && overlay.handleCharacterTyped(event.getCharacterEvent())) {
            event.setCanceled(true);
        }
    }

    public static void renderNovelOverlay(ScreenEvent.Render.Post event) {
        NovelEditorOverlay overlay = OVERLAYS.get(event.getScreen());
        if (overlay != null) {
            overlay.renderPost(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    public static void handleMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        NovelEditorOverlay overlay = OVERLAYS.get(event.getScreen());
        if (overlay != null && overlay.handleMousePressed(event.getMouseButtonEvent(), event.isDoubleClick())) {
            event.setCanceled(true);
        }
    }

    public static void handleMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        NovelEditorOverlay overlay = OVERLAYS.get(event.getScreen());
        if (overlay != null && overlay.handleMouseDragged(event.getMouseButtonEvent(), event.getDragX(), event.getDragY())) {
            event.setCanceled(true);
        }
    }

    public static void handleMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        NovelEditorOverlay overlay = OVERLAYS.get(event.getScreen());
        if (overlay != null && overlay.handleMouseReleased(event.getMouseButtonEvent())) {
            event.setCanceled(true);
        }
    }

    public static void handleMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        NovelEditorOverlay overlay = OVERLAYS.get(event.getScreen());
        if (overlay != null && overlay.handleMouseScrolled(event.getMouseX(), event.getMouseY(),
                event.getScrollDeltaX(), event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    public static void removeOverlay(ScreenEvent.Closing event) {
        OVERLAYS.remove(event.getScreen());
    }
}
