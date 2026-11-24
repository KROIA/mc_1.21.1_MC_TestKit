package net.kroia.testkit.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GifWriterTest {
    @Test
    void framesScaledDiffedAndPointerDrawn(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a/b.gif");
        try (GifWriter gif = new GifWriter(file, 800, 100)) {
            gif.add(solid(1920, 1080, Color.BLUE), Double.NaN, Double.NaN);
            gif.add(solid(1920, 1080, Color.BLUE), Double.NaN, Double.NaN); // unchanged: 1-pixel frame
            BufferedImage red = solid(1920, 1080, Color.BLUE);
            var g = red.getGraphics();
            g.setColor(Color.RED);
            g.fillRect(0, 0, 200, 200);
            gif.add(red, 0.5, 0.5);
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(in);
            assertEquals(3, reader.getNumImages(true));
            BufferedImage first = reader.read(0);
            assertEquals(800, first.getWidth());
            assertEquals(450, first.getHeight());
            assertEquals(1, reader.read(1).getWidth(), "unchanged frame stores one pixel");
            BufferedImage third = reader.read(2);
            assertTrue(third.getWidth() < 800, "only the changed part is stored, got " + third.getWidth());
        }
        // the pointer (white arrow, tip at the centre) shows in the final picture
        BufferedImage last = lastFrame(file);
        assertEquals(Color.WHITE.getRGB(), last.getRGB(402, 232) | 0xFF000000, "pointer pixel");
        assertEquals(Color.BLUE.getRGB(), last.getRGB(700, 400) | 0xFF000000, "background kept from frame 1");
    }

    private static BufferedImage solid(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.getGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        return img;
    }

    /** Composes all frames at their positions, as a viewer shows the end of the animation. */
    private static BufferedImage lastFrame(Path file) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(in);
            BufferedImage canvas = new BufferedImage(800, 450, BufferedImage.TYPE_INT_RGB);
            var g = canvas.getGraphics();
            for (int i = 0; i < reader.getNumImages(true); i++) {
                var desc = (javax.imageio.metadata.IIOMetadataNode) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                var d = (javax.imageio.metadata.IIOMetadataNode) desc.getElementsByTagName("ImageDescriptor").item(0);
                g.drawImage(reader.read(i), Integer.parseInt(d.getAttribute("imageLeftPosition")),
                        Integer.parseInt(d.getAttribute("imageTopPosition")), null);
            }
            return canvas;
        }
    }
}
