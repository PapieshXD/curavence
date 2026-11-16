package com.ralek.backend.imaging.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

@Component
public class NiftiReader {

    private static final int HEADER_SIZE = 348;
    private static final int SINGLE_FILE_MIN_OFFSET = 352;

    NiftiVolume read(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] headerBytes = input.readNBytes(SINGLE_FILE_MIN_OFFSET);
            if (headerBytes.length < SINGLE_FILE_MIN_OFFSET) {
                throw invalid("Plik NIfTI jest za krótki");
            }

            ByteOrder order = byteOrder(headerBytes);
            ByteBuffer header = ByteBuffer.wrap(headerBytes).order(order);
            String magic = new String(headerBytes, 344, 3, StandardCharsets.US_ASCII);
            if (!"n+1".equals(magic)) {
                throw invalid("Obsługiwany jest pojedynczy plik NIfTI-1 (.nii lub .nii.gz)");
            }

            int dimensions = unsignedShort(header.getShort(40));
            if (dimensions < 3 || dimensions > 7) {
                throw invalid("NIfTI musi zawierać co najmniej trójwymiarowy obraz");
            }
            int width = positiveDimension(header.getShort(42), "X");
            int height = positiveDimension(header.getShort(44), "Y");
            int depth = positiveDimension(header.getShort(46), "Z");
            int frames = dimensions >= 4 ? positiveDimension(header.getShort(48), "T") : 1;
            for (int index = 5; index <= dimensions; index++) {
                int value = unsignedShort(header.getShort(40 + index * 2));
                if (value > 1) {
                    throw invalid("Wektorowe dane NIfTI (wymiary 5-7) nie są obsługiwane");
                }
            }
            if (width > 8192 || height > 8192) {
                throw invalid("Wymiary pojedynczego przekroju NIfTI są zbyt duże");
            }

            int datatype = unsignedShort(header.getShort(70));
            int bytesPerVoxel = bytesPerVoxel(datatype);
            int bitpix = unsignedShort(header.getShort(72));
            if (bitpix != bytesPerVoxel * 8) {
                throw invalid("Pole bitpix nie odpowiada typowi danych NIfTI");
            }

            float offsetValue = header.getFloat(108);
            if (!Float.isFinite(offsetValue)) throw invalid("Nieprawidłowy vox_offset w NIfTI");
            long voxelOffset = Math.round(offsetValue);
            if (voxelOffset < SINGLE_FILE_MIN_OFFSET) {
                throw invalid("Dane obrazu NIfTI zaczynają się przed końcem nagłówka");
            }

            long voxelCount;
            long dataBytes;
            try {
                voxelCount = Math.multiplyExact(Math.multiplyExact((long) width, height),
                        Math.multiplyExact((long) depth, frames));
                dataBytes = Math.multiplyExact(voxelCount, bytesPerVoxel);
            } catch (ArithmeticException exception) {
                throw invalid("Wymiary obrazu NIfTI są zbyt duże");
            }
            long requiredFileBytes;
            try {
                requiredFileBytes = Math.addExact(voxelOffset, dataBytes);
            } catch (ArithmeticException exception) {
                throw invalid("Wymiary obrazu NIfTI są zbyt duże");
            }
            if (Files.size(path) < requiredFileBytes) {
                throw invalid("Plik NIfTI nie zawiera wszystkich zadeklarowanych wokseli");
            }

            double slope = finiteOr(header.getFloat(112), 0.0);
            if (slope == 0.0) slope = 1.0;
            double intercept = finiteOr(header.getFloat(116), 0.0);
            NiftiGeometry geometry = geometry(header);
            return new NiftiVolume(path, order, width, height, depth, frames, datatype,
                    bytesPerVoxel, voxelOffset, slope, intercept, geometry);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nie udało się odczytać pliku NIfTI", exception);
        }
    }

    private ByteOrder byteOrder(byte[] bytes) {
        int little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        if (little == HEADER_SIZE) return ByteOrder.LITTLE_ENDIAN;
        int big = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(0);
        if (big == HEADER_SIZE) return ByteOrder.BIG_ENDIAN;
        throw invalid("Nie rozpoznano nagłówka NIfTI-1");
    }

    private NiftiGeometry geometry(ByteBuffer header) {
        double unit = spatialUnitScale(header.get(123));
        double[] pixdim = new double[8];
        for (int index = 0; index < pixdim.length; index++) {
            pixdim[index] = Math.abs(finiteOr(header.getFloat(76 + index * 4), 1.0));
            if (pixdim[index] == 0.0) pixdim[index] = 1.0;
        }

        int sformCode = unsignedShort(header.getShort(254));
        if (sformCode > 0) {
            double[][] rows = new double[3][4];
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 4; column++) {
                    rows[row][column] = finiteOr(header.getFloat(280 + row * 16 + column * 4), 0.0) * unit;
                }
            }
            return new NiftiGeometry(
                    new double[]{rows[0][3], rows[1][3], rows[2][3]},
                    new double[]{rows[0][0], rows[1][0], rows[2][0]},
                    new double[]{rows[0][1], rows[1][1], rows[2][1]},
                    new double[]{rows[0][2], rows[1][2], rows[2][2]});
        }

        int qformCode = unsignedShort(header.getShort(252));
        if (qformCode > 0) {
            double b = finiteOr(header.getFloat(256), 0.0);
            double c = finiteOr(header.getFloat(260), 0.0);
            double d = finiteOr(header.getFloat(264), 0.0);
            double aa = 1.0 - (b * b + c * c + d * d);
            double a;
            if (aa < 1.0e-7) {
                double length = Math.sqrt(b * b + c * c + d * d);
                if (length == 0.0) {
                    a = 1.0;
                } else {
                    b /= length;
                    c /= length;
                    d /= length;
                    a = 0.0;
                }
            } else {
                a = Math.sqrt(aa);
            }
            double[][] rotation = {
                    {a * a + b * b - c * c - d * d, 2 * b * c - 2 * a * d, 2 * b * d + 2 * a * c},
                    {2 * b * c + 2 * a * d, a * a + c * c - b * b - d * d, 2 * c * d - 2 * a * b},
                    {2 * b * d - 2 * a * c, 2 * c * d + 2 * a * b, a * a + d * d - c * c - b * b}
            };
            double qfac = header.getFloat(76) < 0 ? -1.0 : 1.0;
            return new NiftiGeometry(
                    new double[]{header.getFloat(268) * unit, header.getFloat(272) * unit, header.getFloat(276) * unit},
                    column(rotation, 0, pixdim[1] * unit),
                    column(rotation, 1, pixdim[2] * unit),
                    column(rotation, 2, pixdim[3] * qfac * unit));
        }

        return new NiftiGeometry(new double[]{0.0, 0.0, 0.0},
                new double[]{pixdim[1] * unit, 0.0, 0.0},
                new double[]{0.0, pixdim[2] * unit, 0.0},
                new double[]{0.0, 0.0, pixdim[3] * unit});
    }

    private double[] column(double[][] matrix, int column, double scale) {
        return new double[]{matrix[0][column] * scale, matrix[1][column] * scale, matrix[2][column] * scale};
    }

    private double spatialUnitScale(byte xyztUnits) {
        return switch (Byte.toUnsignedInt(xyztUnits) & 0x07) {
            case 1 -> 1000.0;
            case 3 -> 0.001;
            default -> 1.0;
        };
    }

    private int bytesPerVoxel(int datatype) {
        return switch (datatype) {
            case 2, 256 -> 1;
            case 4, 512 -> 2;
            case 8, 16, 768 -> 4;
            case 64 -> 8;
            default -> throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Nieobsługiwany typ wokseli NIfTI: " + datatype);
        };
    }

    private int positiveDimension(short value, String name) {
        int result = unsignedShort(value);
        if (result < 1) throw invalid("Nieprawidłowy wymiar " + name + " w NIfTI");
        return result;
    }

    private int unsignedShort(short value) {
        return Short.toUnsignedInt(value);
    }

    private double finiteOr(float value, double fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

record NiftiVolume(Path path, ByteOrder byteOrder, int width, int height, int depth, int frames,
                   int datatype, int bytesPerVoxel, long voxelOffset, double scaleSlope,
                   double scaleIntercept, NiftiGeometry geometry) {
    long voxelsPerSlice() {
        return (long) width * height;
    }

    long sliceBytes() {
        return voxelsPerSlice() * bytesPerVoxel;
    }

    int instanceCount() {
        return Math.multiplyExact(depth, frames);
    }
}

record NiftiGeometry(double[] originRas, double[] axisIRas, double[] axisJRas, double[] axisKRas) {
    NiftiGeometry {
        originRas = Arrays.copyOf(originRas, 3);
        axisIRas = Arrays.copyOf(axisIRas, 3);
        axisJRas = Arrays.copyOf(axisJRas, 3);
        axisKRas = Arrays.copyOf(axisKRas, 3);
    }
}
