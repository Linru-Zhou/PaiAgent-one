package com.paiagent.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageTextOverlayTest {

    private static final Color BACKGROUND = new Color(30, 40, 70);
    private final ImageTextOverlay overlay = new ImageTextOverlay();

    private static byte[] solidPng(int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, size, size);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    /** 区域内"几乎白色"的像素个数（文字填充是纯白，背景是深蓝）。 */
    private static int brightPixels(BufferedImage image, int x0, int y0, int x1, int y1) {
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 255) > 220 && ((rgb >> 8) & 255) > 220 && (rgb & 255) > 220) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void drawsTheTitleAtTheTopAndLeavesTheRestOfTheImageUntouched() throws Exception {
        byte[] result = overlay.draw(solidPng(600), "HashMap 并发不安全\nConcurrentHashMap", "top");

        BufferedImage image = decode(result);
        assertEquals(600, image.getWidth());
        assertEquals(600, image.getHeight());
        assertTrue(brightPixels(image, 0, 0, 600, 200) > 1500, "顶部要有大片白色文字");
        assertEquals(0, brightPixels(image, 0, 300, 600, 600), "下半部分不能被动过");

        // 写给人看的样图，肉眼核对字形是否正确（mvn 的 target 目录，不进仓库）
        Path sample = Path.of("target", "overlay-sample.png");
        Files.createDirectories(sample.getParent());
        Files.write(sample, result);
    }

    @Test
    void aScrimKeepsTheTextReadableWhenTheArtworkRunsIntoIt() throws Exception {
        // 模拟模型把图案画到了文字区域：整张图是纯白，文字下面没有深色背景
        BufferedImage white = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        var g = white.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 600);
        g.dispose();
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        ImageIO.write(white, "png", in);

        BufferedImage image = decode(overlay.draw(in.toByteArray(), "并发不安全\nConcurrentHashMap", "top"));
        int topCorner = image.getRGB(5, 5);
        assertTrue(((topCorner >> 16) & 255) < 110, "文字衬底要把顶部压暗，否则白字落在白底上看不见");
        assertEquals(0xFFFFFF, image.getRGB(300, 590) & 0xFFFFFF, "远离文字的地方不能被动过");
    }

    @Test
    void bottomAndCenterPositionsMoveTheText() throws Exception {
        BufferedImage bottom = decode(overlay.draw(solidPng(600), "并发不安全", "bottom"));
        assertEquals(0, brightPixels(bottom, 0, 0, 600, 300), "bottom：上半部分没有文字");
        assertTrue(brightPixels(bottom, 0, 400, 600, 600) > 500, "bottom：文字在下方");

        BufferedImage center = decode(overlay.draw(solidPng(600), "并发不安全", "center"));
        assertEquals(0, brightPixels(center, 0, 0, 600, 200), "center：顶部没有文字");
        assertEquals(0, brightPixels(center, 0, 400, 600, 600), "center：底部没有文字");
        assertTrue(brightPixels(center, 0, 200, 600, 400) > 500, "center：文字在中间");
    }

    @Test
    void aLongTitleShrinksToStayInsideTheImage() throws Exception {
        BufferedImage image = decode(overlay.draw(solidPng(600), "ConcurrentHashMap并发容器的分段锁与CAS实现原理", "top"));
        int left = 0;
        int right = 0;
        for (int x = 0; x < 600; x++) {
            if (brightPixels(image, x, 0, x + 1, 600) > 0) {
                if (left == 0 && right == 0) {
                    left = x;
                }
                right = x;
            }
        }
        assertTrue(left >= 600 * 0.05, "左边留白，实际最左 " + left);
        assertTrue(right <= 600 * 0.95, "右边留白，实际最右 " + right);
    }

    @Test
    void noTextMeansTheOriginalBytesAreReturnedUntouched() throws Exception {
        byte[] original = solidPng(64);
        assertSame(original, overlay.draw(original, null, "top"));
        assertSame(original, overlay.draw(original, "  \n \n", "top"));
    }

    @Test
    void anUndecodableImageFailsLoudlyInsteadOfShippingAnImageWithoutTheTitle() {
        assertThrows(IOException.class, () -> overlay.draw("not an image".getBytes(), "标题", "top"));
    }

    @Test
    void assertCanDrawFailsBeforeAnythingIsPaidFor() {
        assertDoesNotThrow(() -> overlay.assertCanDraw("HashMap 并发不安全\nConcurrentHashMap"));
        assertDoesNotThrow(() -> overlay.assertCanDraw(null), "没有叠加文字就不检查");

        // U+10FFFF 是非字符，任何字体都画不出来：模拟"这台机器上没有能显示它的字体"
        IllegalStateException noFont = assertThrows(IllegalStateException.class,
                () -> overlay.assertCanDraw(new String(Character.toChars(0x10FFFF))));
        assertTrue(noFont.getMessage().contains("字体"), noFont.getMessage());

        assertThrows(IllegalArgumentException.class, () -> overlay.assertCanDraw("字".repeat(ImageTextOverlay.MAX_CHARS + 1)));
    }
}
