package me.funclogic.integratedide.client;

import com.mojang.blaze3d.platform.InputConstants;
import me.funclogic.integratedide.IntegratedIde;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.extensions.IKeyMappingExtension;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Client key mappings shown under the Integrated IDE controls category. */
public final class IntegratedIdeKeyMappings {
    public static final String COMPILE_KEY = "key.integratedide.compile";
    public static final String COMPLETION_KEY = "key.integratedide.completion";
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(IntegratedIde.MOD_ID, "integrated_ide"));
    public static final KeyMapping COMPILE_NOVEL = new KeyMapping(COMPILE_KEY, KeyConflictContext.GUI,
            KeyModifier.CONTROL, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, CATEGORY);
    public static final KeyMapping REQUEST_COMPLETION = new KeyMapping(COMPLETION_KEY, KeyConflictContext.GUI,
            KeyModifier.CONTROL, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SPACE, CATEGORY);

    private IntegratedIdeKeyMappings() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(COMPILE_NOVEL);
        event.register(REQUEST_COMPLETION);
    }

    static boolean matchesCompile(KeyEvent event) {
        return matches(COMPILE_NOVEL, event);
    }

    static boolean matchesCompletion(KeyEvent event) {
        return matches(REQUEST_COMPLETION, event);
    }

    /**
     * KeyMapping.matches checks the key code but reads modifier state from the
     * live window. Screen events already carry that state, so compare against
     * the current user binding directly; this preserves both Ctrl defaults and
     * arbitrary rebinding from Minecraft's Controls screen.
     */
    private static boolean matches(KeyMapping mapping, KeyEvent event) {
        IKeyMappingExtension extended = (IKeyMappingExtension) (Object) mapping;
        return extended.getKey().equals(InputConstants.getKey(event)) && switch (extended.getKeyModifier()) {
            case CONTROL -> event.hasControlDown();
            case CONTROL_OR_COMMAND -> event.hasControlDownWithQuirk();
            case SHIFT -> event.hasShiftDown();
            case ALT -> event.hasAltDown();
            case NONE -> !event.hasControlDown() && !event.hasShiftDown() && !event.hasAltDown();
        };
    }
}
