package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Ensures resource-pack language files remain parseable and translation-complete. */
class LanguageResourceTest {
    @Test
    void englishAndChineseLanguageFilesAreValidAndExposeTheSameKeys() throws IOException {
        JsonObject english = read("en_us.json");
        JsonObject chinese = read("zh_cn.json");

        assertFalse(english.entrySet().isEmpty());
        assertEquals(english.keySet(), chinese.keySet());
    }

    private static JsonObject read(String filename) throws IOException {
        Path resource = Path.of("src", "main", "resources", "assets", "integratedide", "lang", filename);
        return JsonParser.parseString(Files.readString(resource, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
