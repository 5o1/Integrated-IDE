package me.funclogic.integratedide.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Matches a newly parsed dependency graph against the last successful graph. */
final class NovelCompilationCache {
    private NovelCompilationCache() {
    }

    static Reconciliation reconcile(ExpressionCompiler.Compilation compilation, List<CachedNode> cachedNodes) {
        Map<String, ArrayDeque<CachedNode>> byFingerprint = new HashMap<>();
        for (CachedNode node : cachedNodes) {
            byFingerprint.computeIfAbsent(node.fingerprint, ignored -> new ArrayDeque<>()).addLast(node);
        }
        Map<String, String> fingerprints = new HashMap<>();
        Map<String, CachedNode> matches = new HashMap<>();
        for (ExpressionCompiler.CardStep step : compilation.steps()) {
            String fingerprint = fingerprint(step, fingerprints);
            fingerprints.put(step.id(), fingerprint);
            ArrayDeque<CachedNode> candidates = byFingerprint.get(fingerprint);
            if (candidates != null && !candidates.isEmpty()) {
                matches.put(step.id(), candidates.removeFirst());
            }
        }
        return new Reconciliation(Map.copyOf(fingerprints), Map.copyOf(matches));
    }

    static BuildSelection select(ExpressionCompiler.Compilation compilation, Reconciliation reconciliation,
                                 Player player, boolean forceRebuildMissingCards) {
        Map<String, ItemStack> available = new HashMap<>();
        List<MissingNode> missingCachedNodes = new ArrayList<>();
        Set<String> rebuilt = new HashSet<>();

        for (ExpressionCompiler.CardStep step : compilation.steps()) {
            if (step.kind() == ExpressionCompiler.StepKind.EXTERNAL_REFERENCE) {
                int id = Integer.parseInt(step.value());
                ItemStack external = CardInventory.findVariableCardById(player, id, step.outputTypeId());
                if (external == null) {
                    return BuildSelection.missingExternal(step, id);
                }
                available.put(step.id(), external.copy());
                continue;
            }
            CachedNode cached = reconciliation.match(step.id());
            if (cached == null || cached.variableCardId < 0) {
                rebuilt.add(step.id());
                continue;
            }
            ItemStack card = CardInventory.findVariableCardById(player, cached.variableCardId, step.outputTypeId());
            if (card == null) {
                missingCachedNodes.add(new MissingNode(step, cached.variableCardId));
            } else {
                available.put(step.id(), card.copy());
            }
        }

        if (!missingCachedNodes.isEmpty() && !forceRebuildMissingCards) {
            return BuildSelection.missingCached(missingCachedNodes);
        }
        for (MissingNode missing : missingCachedNodes) {
            rebuilt.add(missing.step().id());
        }
        propagateToDependents(compilation.steps(), rebuilt);
        available.keySet().removeAll(rebuilt);

        List<ExpressionCompiler.CardStep> stepsToBuild = compilation.steps().stream()
                .filter(ExpressionCompiler.CardStep::createsVariableCard)
                .filter(step -> rebuilt.contains(step.id()))
                .toList();
        return BuildSelection.ready(available, stepsToBuild, missingCachedNodes);
    }

    static List<CachedNode> snapshot(ExpressionCompiler.Compilation compilation, Reconciliation reconciliation,
                                     Map<String, ItemStack> cards) {
        List<CachedNode> snapshot = new ArrayList<>();
        for (ExpressionCompiler.CardStep step : compilation.steps()) {
            int id;
            if (step.kind() == ExpressionCompiler.StepKind.EXTERNAL_REFERENCE) {
                id = Integer.parseInt(step.value());
            } else {
                ItemStack card = cards.get(step.id());
                id = CardInventory.variableCardId(card);
            }
            List<String> inputFingerprints = step.inputs().stream()
                    .map(reconciliation::fingerprint)
                    .toList();
            snapshot.add(new CachedNode(reconciliation.fingerprint(step.id()), id, inputFingerprints));
        }
        return snapshot;
    }

    private static void propagateToDependents(List<ExpressionCompiler.CardStep> steps, Set<String> rebuilt) {
        Map<String, List<String>> dependents = new HashMap<>();
        for (ExpressionCompiler.CardStep step : steps) {
            for (String input : step.inputs()) {
                dependents.computeIfAbsent(input, ignored -> new ArrayList<>()).add(step.id());
            }
        }
        ArrayDeque<String> pending = new ArrayDeque<>(rebuilt);
        while (!pending.isEmpty()) {
            for (String dependent : dependents.getOrDefault(pending.removeFirst(), List.of())) {
                if (rebuilt.add(dependent)) {
                    pending.addLast(dependent);
                }
            }
        }
    }

    private static String fingerprint(ExpressionCompiler.CardStep step, Map<String, String> fingerprints) {
        StringBuilder value = new StringBuilder(step.kind().name())
                .append('\u0000').append(step.value())
                .append('\u0000').append(step.outputTypeId());
        for (String input : step.inputs()) {
            value.append('\u0000').append(fingerprints.get(input));
        }
        return sha256(value.toString());
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256.", error);
        }
    }

    static final class CachedNode {
        String fingerprint;
        int variableCardId;
        List<String> inputFingerprints;

        CachedNode() {
        }

        CachedNode(String fingerprint, int variableCardId, List<String> inputFingerprints) {
            this.fingerprint = fingerprint;
            this.variableCardId = variableCardId;
            this.inputFingerprints = List.copyOf(inputFingerprints);
        }
    }

    record Reconciliation(Map<String, String> fingerprints, Map<String, CachedNode> matches) {
        CachedNode match(String stepId) {
            return matches.get(stepId);
        }

        String fingerprint(String stepId) {
            return fingerprints.get(stepId);
        }
    }

    record MissingNode(ExpressionCompiler.CardStep step, int variableCardId) {
    }

    record BuildSelection(Map<String, ItemStack> availableCards, List<ExpressionCompiler.CardStep> stepsToBuild,
                          List<MissingNode> missingCachedNodes, MissingNode missingExternal) {
        static BuildSelection ready(Map<String, ItemStack> availableCards, List<ExpressionCompiler.CardStep> stepsToBuild,
                                    List<MissingNode> missingCachedNodes) {
            return new BuildSelection(Map.copyOf(availableCards), List.copyOf(stepsToBuild), List.copyOf(missingCachedNodes), null);
        }

        static BuildSelection missingCached(List<MissingNode> missingCachedNodes) {
            return new BuildSelection(Map.of(), List.of(), List.copyOf(missingCachedNodes), null);
        }

        static BuildSelection missingExternal(ExpressionCompiler.CardStep step, int variableCardId) {
            return new BuildSelection(Map.of(), List.of(), List.of(), new MissingNode(step, variableCardId));
        }

        boolean hasMissingExternal() {
            return missingExternal != null;
        }

        boolean needsRebuildConfirmation() {
            return missingExternal == null && !missingCachedNodes.isEmpty() && stepsToBuild.isEmpty();
        }
    }
}
