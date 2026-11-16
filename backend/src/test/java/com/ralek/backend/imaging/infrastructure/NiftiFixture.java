package com.ralek.backend.imaging.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

final class NiftiFixture {

    static final int WIDTH = 8;
    static final int HEIGHT = 6;
    static final int DEPTH = 3;

    private NiftiFixture() { }

    static byte[] nii() {
        return nii(1);
    }

    static byte[] nii(int frames) {
        ByteBuffer file = ByteBuffer.allocate(352 + WIDTH * HEIGHT * DEPTH * frames * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        file.putInt(0, 348);
        file.putShort(40, (short) (frames > 1 ? 4 : 3));
        file.putShort(42, (short) WIDTH);
        file.putShort(44, (short) HEIGHT);
        file.putShort(46, (short) DEPTH);
        file.putShort(48, (short) frames);
        for (int index = 5; index <= 7; index++) file.putShort(40 + index * 2, (short) 1);
        file.putShort(70, (short) 4);
        file.putShort(72, (short) 16);
        file.putFloat(76, 1.0f);
        file.putFloat(80, 0.8f);
        file.putFloat(84, 0.9f);
        file.putFloat(88, 2.5f);
        file.putFloat(108, 352.0f);
        file.putFloat(112, 1.0f);
        file.put(123, (byte) 2);
        file.putShort(254, (short) 1);
        file.putFloat(280, -0.8f);
        file.putFloat(292, 10.0f);
        file.putFloat(300, 0.9f);
        file.putFloat(308, 20.0f);
        file.putFloat(320, 2.5f);
        file.putFloat(324, 30.0f);
        file.put(344, (byte) 'n');
        file.put(345, (byte) '+');
        file.put(346, (byte) '1');
        file.put(347, (byte) 0);
        file.position(352);
        for (int index = 0; index < WIDTH * HEIGHT * DEPTH * frames; index++) {
            file.putShort((short) (index - 72));
        }
        return file.array();
    }

    static byte[] niiGz() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(nii());
        }
        return bytes.toByteArray();
    }

    static byte[] binaryNii() {
        byte[] file = nii();
        Arrays.fill(file, 352, file.length, (byte) 0);
        int pixel = WIDTH * HEIGHT + (HEIGHT / 2) * WIDTH + WIDTH / 2;
        file[352 + pixel * 2] = 1;
        return file;
    }
}
