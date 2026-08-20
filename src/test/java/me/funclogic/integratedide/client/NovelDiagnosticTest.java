package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

class NovelDiagnosticTest {
    @Test
    void prefixesCompilerErrorsSoTheyCannotLookLikeAStatusHint() {
        var compilation = LogicProgrammerCatalog.create().compile("equals()");
        NovelDiagnostic diagnostic = NovelDiagnostic.compilation(compilation);

        assertEquals(NovelDiagnostic.Severity.ERROR, diagnostic.severity());
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class, diagnostic.text().getContents());
        assertEquals("integratedide.diagnostic.compilation", contents.getKey());
        assertEquals(compilation.message(), contents.getArgs()[0]);
    }

    @Test
    void keepsRuntimeRegistryFailuresInTheDiagnosticArea() {
        NovelDiagnostic diagnostic = NovelDiagnostic.runtime(LogicProgrammerCatalog.create().compile(
                "anyEquals(\"$minecraft:cobblestone\".withSize(10).size(), 10)"),
                RuntimeExpressionValidator.Result.failure("Missing test operator"));

        assertEquals(NovelDiagnostic.Severity.ERROR, diagnostic.severity());
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class, diagnostic.text().getContents());
        assertEquals("integratedide.diagnostic.runtime", contents.getKey());
        assertEquals("Missing test operator", contents.getArgs()[0]);
    }
}
