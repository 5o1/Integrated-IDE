package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import org.junit.jupiter.api.Test;

class NovelCompilationCacheTest {
    @Test
    void reusesEveryCachedCardForAnUnchangedProgram() {
        ExpressionCompiler.Compilation program = program("stone");
        NovelCompilationCache.Reconciliation first = NovelCompilationCache.reconcile(program, List.of());

        NovelCompilationCache.Reconciliation repeated = NovelCompilationCache.reconcile(program,
                cachedNodes(program, first));

        assertEquals(100, repeated.match("v0").variableCardId);
        assertEquals(101, repeated.match("v1").variableCardId);
        assertEquals(102, repeated.match("v2").variableCardId);
    }

    @Test
    void changingAnInputInvalidatesItsDependentsButKeepsUnrelatedCards() {
        ExpressionCompiler.Compilation original = program("stone");
        NovelCompilationCache.Reconciliation first = NovelCompilationCache.reconcile(original, List.of());

        NovelCompilationCache.Reconciliation changed = NovelCompilationCache.reconcile(program("dirt"),
                cachedNodes(original, first));

        assertNull(changed.match("v0"));
        assertNull(changed.match("v1"));
        assertEquals(102, changed.match("v2").variableCardId);
    }

    private static List<NovelCompilationCache.CachedNode> cachedNodes(ExpressionCompiler.Compilation program,
                                                                        NovelCompilationCache.Reconciliation graph) {
        return program.steps().stream()
                .map(step -> new NovelCompilationCache.CachedNode(graph.fingerprint(step.id()),
                        100 + Integer.parseInt(step.id().substring(1)),
                        step.inputs().stream().map(graph::fingerprint).toList()))
                .toList();
    }

    private static ExpressionCompiler.Compilation program(String literal) {
        List<ExpressionCompiler.CardStep> steps = List.of(
                new ExpressionCompiler.CardStep("v0", ExpressionCompiler.StepKind.STATIC_TEXT, literal,
                        List.of(), "test:string", 0, literal.length()),
                new ExpressionCompiler.CardStep("v1", ExpressionCompiler.StepKind.DYNAMIC_OPERATOR, "test:wrap",
                        List.of("v0"), "test:string", 0, literal.length()),
                new ExpressionCompiler.CardStep("v2", ExpressionCompiler.StepKind.STATIC_BOOLEAN, "true",
                        List.of(), "test:boolean", literal.length() + 1, literal.length() + 5));
        return new ExpressionCompiler.Compilation(steps, "v1", List.of(new ExpressionCompiler.StatementRoot(0,
                literal.length(), "v1")), Map.of(), -1, "OK");
    }
}
