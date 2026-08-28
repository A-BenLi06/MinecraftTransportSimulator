package minecrafttransportsimulator.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AModelParserCacheTest {
    @AfterEach
    void clearGlobalCache() {
        AModelParser.clearModelCache();
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
}
