package minecrafttransportsimulator.rendering;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import minecrafttransportsimulator.mcinterface.InterfaceManager;

/**
 * Class responsible for parsing OBJ models into arrays that can be fed to the GPU.
 * Much more versatile than the Forge system.
 *
 * @author don_bruce
 */
public final class ModelParserOBJ extends AModelParser {

    @Override
    protected String getModelSuffix() {
        return "obj";
    }

    @Override
    protected List<RenderableVertices> parseModelInternal(String modelLocation) {
        List<RenderableVertices> objectList = new ArrayList<>();
        BufferedReader reader;
        try {
            reader = new BufferedReader(new InputStreamReader(InterfaceManager.coreInterface.getPackResource(modelLocation)));
        } catch (Exception e) {
            throw new NullPointerException("Attempted to parse the OBJ model at: " + modelLocation + " but could not find it.  Check the path and try again.");
        }

        String objectName = null;
        final List<float[]> vertexList = new ArrayList<>();
        final List<float[]> normalList = new ArrayList<>();
        final List<float[]> textureList = new ArrayList<>();
        final IntArrayBuilder objectVertexData = new IntArrayBuilder(1024);
        final IntArrayBuilder faceVertexData = new IntArrayBuilder(12);

        try {
            int lineNumber = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                ++lineNumber;

                //Do normal parsing.
                if (line.startsWith("o ")) {
                    //Found new object name.  If we are parsing an object, finish up parsing and compile the points for it.
                    if (objectName != null) {
                        if (objectVertexData.isEmpty()) {
                            InterfaceManager.coreInterface.logError("Object " + objectName + " found with no faces defined at line: " + lineNumber + " in: " + modelLocation);
                        } else {
                            compileVertexArray(objectList, vertexList, normalList, textureList, objectVertexData, modelLocation, objectName);
                            objectName = null;
                        }
                    }
                    try {
                        objectName = line.trim().substring(2, line.length());
                    } catch (Exception e) {
                        InterfaceManager.coreInterface.logError("Object found with no name at line: " + lineNumber + " of: " + modelLocation + ".  Make sure your model exporter isn't making things into groups rather than objects.");
                    }
                } else if (line.startsWith("v ")) {
                    try {
                        float[] coords = new float[3];
                        line = line.trim().substring(2).trim();
                        coords[0] = Float.parseFloat(line.substring(0, line.indexOf(' ')));
                        coords[1] = Float.parseFloat(line.substring(line.indexOf(' ') + 1, line.lastIndexOf(' ')));
                        coords[2] = Float.parseFloat(line.substring(line.lastIndexOf(' ') + 1));
                        vertexList.add(coords);
                    } catch (Exception e) {
                        InterfaceManager.coreInterface.logError("Could not parse vertex info at line: " + lineNumber + " of: " + modelLocation + " due to bad formatting.  Vertex lines must consist of only three numbers (X, Y, Z).");
                    }
                } else if (line.startsWith("vt ")) {
                    try {
                        float[] coords = new float[2];
                        line = line.trim().substring(3).trim();
                        int space = line.indexOf(' ');
                        int vertexEnd = line.lastIndexOf(' ') == space ? line.length() : line.lastIndexOf(' ');
                        coords[0] = Float.parseFloat(line.substring(0, space));
                        //Need to invert the V of the UV to change from texture origin being top-left to OpenGL origin being bottom-left.
                        coords[1] = 1 - Float.parseFloat(line.substring(space + 1, vertexEnd));
                        textureList.add(coords);
                    } catch (Exception e) {
                        InterfaceManager.coreInterface.logError("Could not parse vertex texture info at line: " + lineNumber + " of: " + modelLocation + " due to bad formatting.  Vertex texture lines must consist of only two numbers (U, V).");
                    }
                } else if (line.startsWith("vn ")) {
                    try {
                        float[] coords = new float[3];
                        line = line.trim().substring(2).trim();
                        coords[0] = Float.parseFloat(line.substring(0, line.indexOf(' ')));
                        coords[1] = Float.parseFloat(line.substring(line.indexOf(' ') + 1, line.lastIndexOf(' ')));
                        coords[2] = Float.parseFloat(line.substring(line.lastIndexOf(' ') + 1));
                        normalList.add(coords);
                    } catch (Exception e) {
                        InterfaceManager.coreInterface.logError("Could not parse normals info at line: " + lineNumber + " of: " + modelLocation + " due to bad formatting.  Normals lines must consist of only three numbers (Xn, Yn, Zn).");
                    }
                } else if (line.startsWith("f ")) {
                    try {
                        parseFace(line, vertexList.size(), textureList.size(), normalList.size(), faceVertexData);
                        appendTriangulatedFace(faceVertexData, objectVertexData);
                    } catch (Exception e) {
                        InterfaceManager.coreInterface.logError("Could not parse face info at line: " + lineNumber + " of: " + modelLocation + " due to bad formatting.  Face lines must consist of sets of three numbers in the format (V1/T1/N1, V2/T2/N2, ...).");
                    }
                }
            }

            //End of file.  Save the last part in process and close the file.
            compileVertexArray(objectList, vertexList, normalList, textureList, objectVertexData, modelLocation, objectName);
            reader.close();
            return objectList;

        } catch (IOException e) {
            throw new IllegalStateException("Could not finish parsing: " + modelLocation + " due to IOException error.  Did the file change state during parsing?");
        }
    }

    private static void parseFace(String line, int vertexCount, int textureCount, int normalCount, IntArrayBuilder faceVertexData) {
        faceVertexData.clear();
        int cursor = 2;
        while (cursor < line.length()) {
            while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) {
                ++cursor;
            }
            if (cursor >= line.length() || line.charAt(cursor) == '#') {
                break;
            }

            int tokenEnd = cursor;
            while (tokenEnd < line.length() && !Character.isWhitespace(line.charAt(tokenEnd))) {
                ++tokenEnd;
            }
            int firstSlash = line.indexOf('/', cursor);
            int secondSlash = firstSlash == -1 ? -1 : line.indexOf('/', firstSlash + 1);
            if (firstSlash < cursor || firstSlash >= tokenEnd || secondSlash <= firstSlash || secondSlash >= tokenEnd) {
                throw new IllegalArgumentException("Face vertex is missing texture or normal indices.");
            }

            faceVertexData.add(resolveIndex(line, cursor, firstSlash, vertexCount));
            faceVertexData.add(resolveIndex(line, firstSlash + 1, secondSlash, textureCount));
            faceVertexData.add(resolveIndex(line, secondSlash + 1, tokenEnd, normalCount));
            cursor = tokenEnd;
        }
    }

    private static int resolveIndex(String line, int start, int end, int elementCount) {
        if (start >= end) {
            throw new IllegalArgumentException("OBJ index is empty.");
        }

        boolean negative = line.charAt(start) == '-';
        int cursor = negative ? start + 1 : start;
        if (cursor >= end) {
            throw new IllegalArgumentException("OBJ index has no digits.");
        }

        int value = 0;
        while (cursor < end) {
            char digit = line.charAt(cursor++);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("OBJ index contains a non-digit character.");
            }
            value = Math.addExact(Math.multiplyExact(value, 10), digit - '0');
        }
        if (value == 0) {
            throw new IllegalArgumentException("OBJ indices are one-based and may not be zero.");
        }

        int resolvedIndex = negative ? elementCount - value : value - 1;
        //Negative indices are relative to the elements available at this line.  Keep positive
        //indices eligible for deferred validation so legacy files with forward references retain
        //the behavior of the previous parser, which resolved all indices after reading the object.
        if (resolvedIndex < 0 || (negative && resolvedIndex >= elementCount)) {
            throw new IndexOutOfBoundsException("OBJ index resolves outside the available element list.");
        }
        return resolvedIndex;
    }

    private static void appendTriangulatedFace(IntArrayBuilder faceVertexData, IntArrayBuilder objectVertexData) {
        int faceVertexCount = faceVertexData.size() / 3;
        if (faceVertexCount < 3) {
            throw new IllegalArgumentException("OBJ faces must contain at least three vertices.");
        }

        objectVertexData.addTriplet(faceVertexData, 0);
        objectVertexData.addTriplet(faceVertexData, 3);
        objectVertexData.addTriplet(faceVertexData, 6);
        for (int vertexIndex = 3; vertexIndex < faceVertexCount; ++vertexIndex) {
            objectVertexData.addTriplet(faceVertexData, 0);
            objectVertexData.addTriplet(faceVertexData, (vertexIndex - 1) * 3);
            objectVertexData.addTriplet(faceVertexData, vertexIndex * 3);
        }
    }

    private static void compileVertexArray(List<RenderableVertices> objectList, List<float[]> vertexList, List<float[]> normalList, List<float[]> textureList, IntArrayBuilder vertexDataSets, String modelLocation, String objectName) {
        if (objectName == null) {
            InterfaceManager.coreInterface.logError("No object name found in the entire OBJ model file of " + modelLocation + ".  Resorting to 'model' as default.  Are you using groups instead of objects by mistake?");
            objectName = "model";
        }

        try {
            //Compile buffer.
            FloatBuffer compiledBuffer = FloatBuffer.allocate(Math.multiplyExact(vertexDataSets.size() / 3, 8));
            for (int dataIndex = 0; dataIndex < vertexDataSets.size(); dataIndex += 3) {
                compiledBuffer.put(normalList.get(vertexDataSets.get(dataIndex + 2)));
                compiledBuffer.put(textureList.get(vertexDataSets.get(dataIndex + 1)));
                compiledBuffer.put(vertexList.get(vertexDataSets.get(dataIndex)));
            }
            compiledBuffer.flip();
            objectList.add(new RenderableVertices(objectName, compiledBuffer, true));
        } catch (Exception e) {
            InterfaceManager.coreInterface.logError("Could not compile points of: " + modelLocation + ":" + objectName + ".  This is likely due to missing UV mapping on some or all faces.");
        }

        //Clear face data as we don't want to compile it on the next pass.
        vertexDataSets.clear();
    }

    /**Primitive growable buffer used to avoid per-index boxing and array allocation while parsing faces.*/
    private static final class IntArrayBuilder {
        private int[] values;
        private int size;

        private IntArrayBuilder(int initialCapacity) {
            values = new int[initialCapacity];
        }

        private void add(int value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        private void addTriplet(IntArrayBuilder source, int sourceIndex) {
            ensureCapacity(size + 3);
            values[size++] = source.values[sourceIndex];
            values[size++] = source.values[sourceIndex + 1];
            values[size++] = source.values[sourceIndex + 2];
        }

        private int get(int index) {
            return values[index];
        }

        private int size() {
            return size;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private void clear() {
            size = 0;
        }

        private void ensureCapacity(int requiredCapacity) {
            if (requiredCapacity > values.length) {
                int newCapacity = Math.max(requiredCapacity, values.length + (values.length >> 1));
                int[] expandedValues = new int[newCapacity];
                System.arraycopy(values, 0, expandedValues, 0, size);
                values = expandedValues;
            }
        }
    }
}
