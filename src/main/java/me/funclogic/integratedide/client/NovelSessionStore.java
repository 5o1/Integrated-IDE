package me.funclogic.integratedide.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Durable client-only Novel source and compiled-graph cache, separated by game context. */
final class NovelSessionStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static FileState fileState;
    private static boolean dirty;

    private NovelSessionStore() {
    }

    static Session current() {
        ensureLoaded();
        String key = scopeKey();
        SavedSession saved = fileState.sessions.computeIfAbsent(key, ignored -> {
            markDirty();
            return new SavedSession();
        });
        return new Session(saved);
    }

    static void flush() {
        if (!dirty) {
            return;
        }
        Path destination = cachePath();
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        try {
            Files.createDirectories(destination.getParent());
            Files.writeString(temporary, GSON.toJson(fileState), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException error) {
            LOGGER.warn("Could not save Integrated IDE Novel session cache; it will retry on the next editor lifecycle action.",
                    error);
        }
    }

    private static void ensureLoaded() {
        if (fileState != null) {
            return;
        }
        Path source = cachePath();
        try {
            if (!Files.exists(source)) {
                fileState = new FileState();
                return;
            }
            fileState = GSON.fromJson(Files.readString(source, StandardCharsets.UTF_8), FileState.class);
            if (fileState == null || fileState.sessions == null) {
                throw new JsonParseException("The cache root is empty or malformed.");
            }
            validate(fileState);
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Integrated IDE discarded an unreadable Novel session cache and started with an empty editor.", error);
            fileState = new FileState();
            markDirty();
        }
    }

    private static Path cachePath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("integratedide")
                .resolve("novel_sessions.json");
    }

    private static void validate(FileState state) {
        for (Map.Entry<String, SavedSession> entry : state.sessions.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().nodes == null) {
                throw new JsonParseException("The cache contains an incomplete session.");
            }
            for (NovelCompilationCache.CachedNode node : entry.getValue().nodes) {
                if (node == null || node.fingerprint == null || node.fingerprint.isBlank() || node.variableCardId < -1
                        || node.inputFingerprints == null) {
                    throw new JsonParseException("The cache contains an invalid graph node.");
                }
            }
        }
    }

    private static String scopeKey() {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData server = minecraft.getCurrentServer();
        if (server != null) {
            return "server-" + hash(server.ip);
        }
        if (minecraft.getSingleplayerServer() != null) {
            return "world-" + hash(minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath().normalize().toString());
        }
        return "unbound";
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256.", error);
        }
    }

    private static void markDirty() {
        dirty = true;
    }

    static final class Session {
        private final SavedSession saved;

        private Session(SavedSession saved) {
            this.saved = saved;
        }

        String source() {
            return saved.source == null ? "" : saved.source;
        }

        void setSource(String source) {
            String normalized = source == null ? "" : source;
            if (!normalized.equals(source())) {
                saved.source = normalized;
                markDirty();
            }
        }

        NovelCompilationCache.Reconciliation reconcile(ExpressionCompiler.Compilation compilation) {
            return NovelCompilationCache.reconcile(compilation, saved.nodes == null ? List.of() : saved.nodes);
        }

        void commit(ExpressionCompiler.Compilation compilation, NovelCompilationCache.Reconciliation reconciliation,
                    Map<String, net.minecraft.world.item.ItemStack> cards) {
            saved.nodes = new ArrayList<>(NovelCompilationCache.snapshot(compilation, reconciliation, cards));
            markDirty();
        }
    }

    private static final class FileState {
        Map<String, SavedSession> sessions = new LinkedHashMap<>();
    }

    private static final class SavedSession {
        String source = "";
        List<NovelCompilationCache.CachedNode> nodes = new ArrayList<>();
    }
}
