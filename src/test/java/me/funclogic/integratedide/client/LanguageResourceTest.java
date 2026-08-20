package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        String path = "assets/integratedide/lang/" + filename;
        try (InputStream resource = LanguageResourceTest.class.getClassLoader().getResourceAsStream(path)) {
            if (resource == null) {
                throw new IOException("Missing packaged language resource: " + path);
            }
            return JsonParser.parseString(new String(resource.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
