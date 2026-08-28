package minecrafttransportsimulator.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import minecrafttransportsimulator.rendering.ModelParserOBJ.IntArrayBuilder;

class ModelParserOBJTest {
    @Test
    void quadTriangulatesIntoAStableFanWithoutBoxing() {
        IntArrayBuilder face = new IntArrayBuilder(1);
        IntArrayBuilder object = new IntArrayBuilder(1);

        ModelParserOBJ.parseFace("f 1/1/1 2/2/2 3/3/3 4/4/4", 4, 4, 4, face);
        ModelParserOBJ.appendTriangulatedFace(face, object);

        int[] expected = {
                0, 0, 0, 1, 1, 1, 2, 2, 2,
                0, 0, 0, 2, 2, 2, 3, 3, 3
        };
        assertEquals(expected.length, object.size());
        for (int index = 0; index < expected.length; ++index) {
            assertEquals(expected[index], object.get(index));
        }
    }

    @Test
    void negativeIndicesResolveAgainstElementsAvailableOnTheFaceLine() {
        IntArrayBuilder face = new IntArrayBuilder(9);
        ModelParserOBJ.parseFace("f -3/-3/-3 -2/-2/-2 -1/-1/-1", 3, 3, 3, face);

        for (int index = 0; index < face.size(); ++index) {
            assertEquals(index / 3, face.get(index));
        }
    }

    @Test
    void positiveForwardReferenceKeepsLegacyDeferredValidation() {
        assertEquals(4, ModelParserOBJ.resolveIndex("5", 0, 1, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> ModelParserOBJ.resolveIndex("-5", 0, 2, 3));
    }

    @Test
    void malformedOrDegenerateFacesAreRejected() {
        IntArrayBuilder face = new IntArrayBuilder(9);
        IntArrayBuilder object = new IntArrayBuilder(9);

        assertThrows(IllegalArgumentException.class, () -> ModelParserOBJ.parseFace("f 1//1 2//2 3//3", 3, 0, 3, face));
        ModelParserOBJ.parseFace("f 1/1/1 2/2/2", 2, 2, 2, face);
        assertThrows(IllegalArgumentException.class, () -> ModelParserOBJ.appendTriangulatedFace(face, object));
    }
}
