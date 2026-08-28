package minecrafttransportsimulator.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import minecrafttransportsimulator.jsondefs.JSONConfigClient;
import minecrafttransportsimulator.systems.ConfigSystem;

class AModelParserCacheTest {
    private JSONConfigClient originalClientConfig;

    @BeforeEach
    void saveClientConfig() {
        originalClientConfig = ConfigSystem.client;
    }

    @AfterEach
    void clearGlobalCache() {
        AModelParser.clearModelCache();
        ConfigSystem.client = originalClientConfig;
    }

    @SuppressWarnings("unchecked")
    @Test
    void clearReleasesParsedModelsAndMissingTemplate() throws ReflectiveOperationException {
        AModelParser.clearModelCache();
        Field parsedVerticesField = AModelParser.class.getDeclaredField("parsedVertices");
        parsedVerticesField.setAccessible(true);
        Map<String, List<RenderableVertices>> parsedVertices = (Map<String, List<RenderableVertices>>) parsedVerticesField.get(null);

        Field missingTemplateField = AModelParser.class.getDeclaredField("missingModelTemplate");
        missingTemplateField.setAccessible(true);

        List<RenderableVertices> cachedModel = new ArrayList<>();
        cachedModel.add(new RenderableVertices("cached", FloatBuffer.allocate(8), true));
        parsedVertices.put("test:cached.obj", cachedModel);

        List<RenderableVertices> missingTemplate = new ArrayList<>();
        missingTemplate.add(new RenderableVertices(AModelParser.MISSING_MODEL_NAME, FloatBuffer.allocate(16), false, true));
        missingTemplateField.set(null, missingTemplate);

        assertEquals(1, AModelParser.getCachedModelCount());
        assertEquals(24L * Float.BYTES, AModelParser.getCachedVertexDataBytes());

        AModelParser.clearModelCache();

        assertEquals(0, AModelParser.getCachedModelCount());
        assertEquals(0L, AModelParser.getCachedVertexDataBytes());
        assertNull(missingTemplateField.get(null));
    }

    @Test
    void evictsLeastRecentlyUsedModelsByVertexPayload() {
        ConfigSystem.client = new JSONConfigClient();
        ConfigSystem.client.renderingSettings.modelCacheMaxMiB.value = 1;
        new CacheTestParser();

        List<RenderableVertices> first = AModelParser.parseModel("first.cachetest", true);
        List<RenderableVertices> second = AModelParser.parseModel("second.cachetest", true);

        assertEquals(1, AModelParser.getCachedModelCount());
        assertTrue(AModelParser.getCachedVertexDataBytes() <= 1024L * 1024L);
        assertSame(second, AModelParser.parseModel("second.cachetest", true));

        List<RenderableVertices> firstReloaded = AModelParser.parseModel("first.cachetest", true);
        assertEquals(1, AModelParser.getCachedModelCount());
        assertTrue(first != firstReloaded);
    }

    private static final class CacheTestParser extends AModelParser {
        @Override
        protected String getModelSuffix() {
            return "cachetest";
        }

        @Override
        protected List<RenderableVertices> parseModelInternal(String modelLocation) {
            List<RenderableVertices> model = new ArrayList<>();
            model.add(new RenderableVertices(modelLocation, FloatBuffer.allocate(200_000), true));
            return model;
        }
    }
}
