package minecrafttransportsimulator.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.FloatBuffer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RenderableVerticesCacheTest {
    @AfterEach
    void clearCache() {
        RenderableVertices.clearDerivedGeometryCache();
    }

    @Test
    void sharesDerivedGeometryUntilSourceChanges() {
        RenderableVertices source = new RenderableVertices("body", triangle(), true);

        RenderableVertices firstOverlay = source.getOrCreateOverlay(0.001F);
        RenderableVertices firstBackface = source.getOrCreateBackface();
        assertSame(firstOverlay, source.getOrCreateOverlay(0.001F));
        assertSame(firstBackface, source.getOrCreateBackface());
        assertEquals(2, RenderableVertices.getDerivedGeometryCount());

        source.setTextureBounds(0.1F, 0.9F, 0.2F, 0.8F);

        assertNotSame(firstOverlay, source.getOrCreateOverlay(0.001F));
        assertNotSame(firstBackface, source.getOrCreateBackface());
        assertEquals(2, RenderableVertices.getDerivedGeometryCount());
    }

    @Test
    void modelCacheClearAlsoReleasesDerivedGeometry() {
        RenderableVertices source = new RenderableVertices("body", triangle(), true);
        source.getOrCreateOverlay(0.001F);
        assertEquals(1, RenderableVertices.getDerivedGeometryCount());

        AModelParser.clearModelCache();

        assertEquals(0, RenderableVertices.getDerivedGeometryCount());
    }

    private static FloatBuffer triangle() {
        FloatBuffer vertices = FloatBuffer.allocate(24);
        for (int vertex = 0; vertex < 3; ++vertex) {
            vertices.put(0).put(1).put(0);
            vertices.put(0).put(0);
            vertices.put(vertex).put(0).put(0);
        }
        vertices.flip();
        return vertices;
    }
}
