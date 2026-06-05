package usecase.lcl.flow;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class FlowImageWriter {

    private static final int DEFAULT_CELL_WIDTH = 32;
    private static final int DEFAULT_CELL_HEIGHT = 24;

    private FlowImageWriter() {
    }

    public static void writeSnapshotImages(StreamFlowInstrumentation.Snapshot snapshot, Path outputDir)
            throws IOException {
        Files.createDirectories(outputDir);
        writeGrayscalePng(snapshot.tupleCounts(), outputDir.resolve("tuple-flow.png"));
        writeGrayscalePng(snapshot.keyCounts(), outputDir.resolve("key-flow.png"));
    }

    public static void writeGrayscalePng(long[][] matrix, Path outputPath) throws IOException {
        writeGrayscalePng(matrix, outputPath, DEFAULT_CELL_WIDTH, DEFAULT_CELL_HEIGHT);
    }

    public static void writeGrayscalePng(long[][] matrix, Path outputPath, int cellWidth, int cellHeight)
            throws IOException {
        if (cellWidth <= 0 || cellHeight <= 0) {
            throw new IllegalArgumentException("Cell dimensions must be positive");
        }
        int rows = matrix.length;
        int cols = columnCount(matrix);
        int width = Math.max(1, cols * cellWidth);
        int height = Math.max(1, rows * cellHeight);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, width, height);
            long max = max(matrix);
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < matrix[row].length; col++) {
                    int intensity = max == 0L ? 0 : (int) Math.round((255.0 * matrix[row][col]) / max);
                    graphics.setColor(new Color(intensity, intensity, intensity));
                    graphics.fillRect(col * cellWidth, row * cellHeight, cellWidth, cellHeight);
                }
            }
        } finally {
            graphics.dispose();
        }
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", outputPath.toFile());
    }

    private static int columnCount(long[][] matrix) {
        int cols = 0;
        for (long[] row : matrix) {
            cols = Math.max(cols, row.length);
        }
        return cols;
    }

    private static long max(long[][] matrix) {
        long max = 0L;
        for (long[] row : matrix) {
            for (long value : row) {
                max = Math.max(max, value);
            }
        }
        return max;
    }
}
