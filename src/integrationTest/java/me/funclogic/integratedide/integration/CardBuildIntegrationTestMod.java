package me.funclogic.integratedide.integration;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Test-source-only server mod. It is loaded solely by the integration run so
 * that the production mod may remain strictly client-only.
 */
@Mod("integratedide_test")
public final class CardBuildIntegrationTestMod {
    private static final Logger LOGGER = LogUtils.getLogger();

    public CardBuildIntegrationTestMod(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener(this::runAssertions);
    }

    private void runAssertions(ServerStartedEvent event) {
        int exitCode;
        try {
            CardBuildWorkflowTest.verify(event.getServer());
            LOGGER.info("Integrated IDE real Logic Programmer integration tests passed.");
            exitCode = 0;
        } catch (RuntimeException | AssertionError error) {
            LOGGER.error("Integrated IDE real Logic Programmer integration test failed.", error);
            exitCode = 1;
        }
        stopFromOutsideTheServerThread(event.getServer(), exitCode);
    }

    /**
     * The lifecycle callback itself runs on the server thread. Stopping from
     * that thread makes the server wait for itself, so this dedicated test
     * thread is triggered directly by the completed matrix instead.
     */
    private static void stopFromOutsideTheServerThread(net.minecraft.server.MinecraftServer server, int exitCode) {
        Thread.ofPlatform().name("Integrated IDE integration-test shutdown").start(() -> {
            server.halt(true);
            System.exit(exitCode);
        });
    }
}
