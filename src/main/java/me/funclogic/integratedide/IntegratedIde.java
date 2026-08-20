package me.funclogic.integratedide;

import com.mojang.logging.LogUtils;
import me.funclogic.integratedide.client.IntegratedIdeKeyMappings;
import me.funclogic.integratedide.client.LogicProgrammerScreenHooks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** Entry point. This addon has no common/server code and no custom payloads. */
@Mod(value = IntegratedIde.MOD_ID, dist = Dist.CLIENT)
public final class IntegratedIde {
    public static final String MOD_ID = "integratedide";
    public static final Logger LOGGER = LogUtils.getLogger();

    public IntegratedIde(IEventBus modBus, ModContainer container) {
        modBus.addListener(IntegratedIdeKeyMappings::register);
        NeoForge.EVENT_BUS.addListener(LogicProgrammerScreenHooks::addNovelModeControls);
        NeoForge.EVENT_BUS.addListener(LogicProgrammerScreenHooks::handleKeyPressed);
        NeoForge.EVENT_BUS.addListener(LogicProgrammerScreenHooks::handleCharacterTyped);
        NeoForge.EVENT_BUS.addListener(LogicProgrammerScreenHooks::renderNovelOverlay);
        NeoForge.EVENT_BUS.addListener(LogicProgrammerScreenHooks::tickNovelBuilds);
        NeoForge.EVENT_BUS.addListener(LogicProgrammerScreenHooks::handleMousePressed);
        NeoForge.EVENT_BUS.addListener(LogicProgrammerScreenHooks::handleMouseDragged);
        NeoForge.EVENT_BUS.addListener(LogicProgrammerScreenHooks::handleMouseReleased);
        NeoForge.EVENT_BUS.addListener(LogicProgrammerScreenHooks::handleMouseScrolled);
        NeoForge.EVENT_BUS.addListener(LogicProgrammerScreenHooks::removeOverlay);
        LOGGER.info("Loaded Integrated IDE client helper");
    }
}
