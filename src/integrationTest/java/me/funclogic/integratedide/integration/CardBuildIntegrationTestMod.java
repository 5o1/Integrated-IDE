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
        try {
            CardBuildWorkflowTest.verify(event.getServer());
            LOGGER.info("Integrated IDE real Logic Programmer integration tests passed.");
            // This handler runs on the server thread. Calling halt here makes
            // that thread wait for itself; direct process exit is the only
            // deterministic terminal signal for this isolated CI JVM.
            System.exit(0);
        } catch (RuntimeException | AssertionError error) {
            LOGGER.error("Integrated IDE real Logic Programmer integration test failed.", error);
            // NeoForge logs and continues after exceptions from lifecycle
            // listeners. Terminate this test-only JVM explicitly so a failed
            // server workflow can never produce a green Gradle build.
            System.exit(1);
        }
    }
}
