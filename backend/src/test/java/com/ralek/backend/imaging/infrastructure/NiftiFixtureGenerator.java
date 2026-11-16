package com.ralek.backend.imaging.infrastructure;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/** Generates an anonymous synthetic NIfTI-1 volume for local end-to-end QA. */
public final class NiftiFixtureGenerator {

    private NiftiFixtureGenerator() { }

    public static void main(String[] args) throws Exception {
        int width = 64;
        int height = 64;
        int depth = 12;
        ByteBuffer file = ByteBuffer.allocate(352 + width * height * depth * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        file.putInt(0, 348);
        file.putShort(40, (short) 3);
        file.putShort(42, (short) width);
        file.putShort(44, (short) height);
        file.putShort(46, (short) depth);
        for (int index = 4; index <= 7; index++) file.putShort(40 + index * 2, (short) 1);
        file.putShort(70, (short) 4);
        file.putShort(72, (short) 16);
        file.putFloat(76, 1.0f);
        file.putFloat(80, 1.0f);
        file.putFloat(84, 1.0f);
        file.putFloat(88, 2.0f);
        file.putFloat(108, 352.0f);
        file.putFloat(112, 1.0f);
        file.put(123, (byte) 2);
        file.putShort(254, (short) 1);
        file.putFloat(280, -1.0f);
        file.putFloat(292, 32.0f);
        file.putFloat(300, 1.0f);
        file.putFloat(308, -32.0f);
        file.putFloat(320, 2.0f);
        file.putFloat(324, -12.0f);
        file.put(344, (byte) 'n');
        file.put(345, (byte) '+');
        file.put(346, (byte) '1');
        file.position(352);
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double dx = x - 31.5;
                    double dy = y - 31.5;
                    double radius = Math.sqrt(dx * dx + dy * dy);
                    double zScale = 1.0 - Math.abs(z - (depth - 1) / 2.0) / depth;
                    int value = radius < 26 * zScale
                            ? (int) (1400 - radius * 28 + 160 * Math.sin((x + y + z) / 7.0))
                            : 0;
                    file.putShort((short) Math.max(0, value));
                }
            }
        }

        Path output = Path.of(args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (OutputStream gzip = new GZIPOutputStream(Files.newOutputStream(output))) {
            gzip.write(file.array());
        }
        System.out.println(output.toAbsolutePath());
    }
}
