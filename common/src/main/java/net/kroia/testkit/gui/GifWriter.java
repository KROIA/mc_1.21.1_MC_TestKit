package net.kroia.testkit.gui;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Animated GIF from screen frames, with plain Java (javax.imageio): each frame is scaled down to at most
 * {@code maxWidth}, gets a drawn mouse pointer, and only the part that changed since the previous frame is stored
 * (a still GUI costs almost nothing). Colours are reduced per frame by ImageIO's palette builder (256 colours, no
 * dithering). Not thread-safe: feed it from one thread.
 */
final class GifWriter implements Closeable {
    /** Arrow pointer, tip at (0, 0), in output pixels. */
    private static final Polygon ARROW = new Polygon(new int[]{0, 0, 4, 7, 9, 6, 11}, new int[]{0, 16, 12, 18, 17, 11, 11}, 7);

    private final ImageWriter writer;
    private final ImageOutputStream out;
    private final int maxWidth;
    private final String delayCs;
    private BufferedImage previous;
    private int frames;

    /** @param frameMillis how long each frame shows (GIF counts in 1/100 s) */
    GifWriter(Path file, int maxWidth, int frameMillis) throws IOException {
        this.maxWidth = maxWidth;
        this.delayCs = Integer.toString(Math.max(1, Math.round(frameMillis / 10f)));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.deleteIfExists(file);
        writer = ImageIO.getImageWritersByFormatName("gif").next();
        out = ImageIO.createImageOutputStream(file.toFile());
        writer.setOutput(out);
        writer.prepareWriteSequence(null);
    }

    int frames() {
        return frames;
    }

    /**
     * @param pointerX pointer tip as a fraction of the frame width (0..1), NaN: no pointer
     * @param pointerY as a fraction of the frame height
     */
    void add(BufferedImage frame, double pointerX, double pointerY) throws IOException {
        BufferedImage img = scale(frame);
        if (!Double.isNaN(pointerX)) drawPointer(img, (int) Math.round(pointerX * img.getWidth()), (int) Math.round(pointerY * img.getHeight()));
        Rectangle r = previous == null ? new Rectangle(img.getWidth(), img.getHeight()) : changed(previous, img);
        if (r.isEmpty()) r = new Rectangle(1, 1); // unchanged: one pixel keeps the frame's time
        BufferedImage part = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB);
        part.getGraphics().drawImage(img.getSubimage(r.x, r.y, r.width, r.height), 0, 0, null);
        writer.writeToSequence(new IIOImage(part, null, metadata(part, r)), null);
        previous = img;
        frames++;
    }

    @Override
    public void close() throws IOException {
        try {
            writer.endWriteSequence();
        } catch (IllegalStateException e) {
            // no frame written: nothing to end
        } finally {
            writer.dispose();
            out.close();
        }
    }

    /** At most {@code maxWidth} wide, as TYPE_INT_RGB; halves first, so text stays readable (no skipped pixels). */
    private BufferedImage scale(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage cur = src;
        int targetW = Math.min(maxWidth, w);
        while (true) {
            int nw = Math.max(targetW, w / 2);
            int nh = Math.max(1, (int) Math.round((double) h * nw / w));
            if (nw == w && cur.getType() == BufferedImage.TYPE_INT_RGB) return cur;
            BufferedImage next = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cur, 0, 0, nw, nh, null);
            g.dispose();
            cur = next;
            if (nw == targetW) return cur;
            w = nw;
            h = nh;
        }
    }

    private static void drawPointer(BufferedImage img, int x, int y) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(x, y);
        g.setColor(Color.WHITE);
        g.fillPolygon(ARROW);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1f));
        g.drawPolygon(ARROW);
        g.dispose();
    }

    /** Bounding box of the pixels that differ (same size images); empty if none. */
    private static Rectangle changed(BufferedImage a, BufferedImage b) {
        int w = a.getWidth(), h = a.getHeight();
        int[] pa = ((DataBufferInt) a.getRaster().getDataBuffer()).getData();
        int[] pb = ((DataBufferInt) b.getRaster().getDataBuffer()).getData();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (pa[row + x] != pb[row + x]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    maxY = y;
                }
            }
        }
        return maxX < 0 ? new Rectangle() : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private IIOMetadata metadata(BufferedImage part, Rectangle at) throws IOException {
        IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(part), null);
        String format = meta.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);

        IIOMetadataNode gce = child(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "doNotDispose"); // the next frame draws over this one
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("delayTime", delayCs);
        gce.setAttribute("transparentColorIndex", "0");

        IIOMetadataNode desc = child(root, "ImageDescriptor");
        desc.setAttribute("imageLeftPosition", Integer.toString(at.x));
        desc.setAttribute("imageTopPosition", Integer.toString(at.y));
        desc.setAttribute("imageWidth", Integer.toString(at.width));
        desc.setAttribute("imageHeight", Integer.toString(at.height));
        desc.setAttribute("interlaceFlag", "FALSE");

        if (frames == 0) { // loop forever
            IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
            app.setAttribute("applicationID", "NETSCAPE");
            app.setAttribute("authenticationCode", "2.0");
            app.setUserObject(new byte[]{1, 0, 0});
            child(root, "ApplicationExtensions").appendChild(app);
        }
        meta.setFromTree(format, root);
        return meta;
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++)
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
