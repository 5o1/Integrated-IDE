package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NovelDiagnosticTest {
    @Test
    void prefixesCompilerErrorsSoTheyCannotLookLikeAStatusHint() {
        var compilation = LogicProgrammerCatalog.create().compile("equals()");
        NovelDiagnostic diagnostic = NovelDiagnostic.compilation(compilation);

        assertEquals(NovelDiagnostic.Severity.ERROR, diagnostic.severity());
        assertTrue(diagnostic.text().getString().contains(compilation.message()));
    }

    @Test
    void keepsRuntimeRegistryFailuresInTheDiagnosticArea() {
        NovelDiagnostic diagnostic = NovelDiagnostic.runtime(LogicProgrammerCatalog.create().compile(
                "anyEquals(\"$minecraft:cobblestone\".withSize(10).size(), 10)"),
                RuntimeExpressionValidator.Result.failure("Missing test operator"));

        assertEquals(NovelDiagnostic.Severity.ERROR, diagnostic.severity());
        assertTrue(diagnostic.text().getString().contains("Missing test operator"));
    }
}
