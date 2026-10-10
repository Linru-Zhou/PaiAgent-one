package com.paiagent.service;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把标题文字用代码画到生成好的图片上。
 *
 * 为什么要这样做：扩散模型画出来的字是"像字的图案"，不是真的写字——实测短中文标题偶尔对、偶尔变成别的字
 * （"HashMap并发风险"出来是"HashMap莾发扃飚"），ConcurrentHashMap 这种长英文标识符在万相 2.2/2.5/2.6 和
 * qwen-image-2.0 上全部拼错。要让封面上的字一定是对的，只能让模型画不带字的背景，再由代码用真字体写上去。
 *
 * 排版：第一行是大标题，其余各行是副标题（字号更小）；白字加深色描边，任何底色上都看得清；
 * 超宽就缩小字号，缩到一半仍放不下才按字符折行。
 */
@Slf4j
public class ImageTextOverlay {

    /** 候选字体，靠前的优先；环境变量 PAIAGENT_OVERLAY_FONT 指定的字体族排在最前。 */
    private static final List<String> FONT_CANDIDATES = List.of(
            "PingFang SC", "Hiragino Sans GB", "Heiti SC", "STHeiti",
            "Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC", "WenQuanYi Micro Hei",
            "Microsoft YaHei", "SimHei", "Arial Unicode MS", Font.SANS_SERIF);

    static final int MAX_CHARS = 80;
    static final int MAX_LINES = 4;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** 清理后的文字行（去空行、去首尾空白、最多 MAX_LINES 行）；没有可画的文字时为空列表。 */
    public static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines.size() > MAX_LINES ? new ArrayList<>(lines.subList(0, MAX_LINES)) : lines;
    }

    /**
     * 调用付费的生图接口之前先检查：文字不能太长，并且这台机器上要有能完整显示它的字体。
     * 否则图片生成出来再失败，钱就白花了。
     */
    public void assertCanDraw(String text) {
        List<String> lines = lines(text);
        if (lines.isEmpty()) {
            return;
        }
        String all = String.join("", lines);
        if (all.length() > MAX_CHARS) {
            throw new IllegalArgumentException("叠加文字太长（" + all.length() + " 个字符，最多 " + MAX_CHARS + "）");
        }
        pickFont(all);
    }

    /** 下载图片并把文字画上去，返回 PNG 字节。 */
    public byte[] drawOnUrl(String imageUrl, String text, String position) throws IOException {
        byte[] original = download(imageUrl);
        return draw(original, text, position);
    }

    public byte[] draw(byte[] imageBytes, String text, String position) throws IOException {
        List<String> lines = lines(text);
        if (lines.isEmpty()) {
            return imageBytes;
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (source == null) {
            throw new IOException("无法解析图片，不能叠加文字");
        }
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            FontRenderContext frc = g.getFontRenderContext();

            String family = pickFont(String.join("", lines));
            int width = image.getWidth();
            int height = image.getHeight();
            double maxWidth = width * 0.88;
            double titleSize = Math.min(width, height) * 0.095;
            double subtitleSize = titleSize * 0.5;

            // 1) 先排版：每一行（超宽就缩小、再不行就折行）变成若干"绘制行"
            List<Shape> shapes = new ArrayList<>();
            List<Double> lineHeights = new ArrayList<>();
            List<Double> strokeWidths = new ArrayList<>();
            double gap = titleSize * 0.28;
            for (int i = 0; i < lines.size(); i++) {
                boolean title = i == 0;
                double base = title ? titleSize : subtitleSize;
                int style = Font.BOLD;
                for (String piece : fit(lines.get(i), family, style, base, maxWidth, frc)) {
                    Font font = fitFont(piece, family, style, base, maxWidth, frc);
                    TextLayout layout = new TextLayout(piece, font, frc);
                    double w = layout.getAdvance();
                    double ascent = layout.getAscent();
                    // 轮廓以基线为原点；先放在 x=0，等整体高度确定后再平移
                    Shape outline = layout.getOutline(AffineTransform.getTranslateInstance((width - w) / 2.0, ascent));
                    shapes.add(outline);
                    lineHeights.add((double) (layout.getAscent() + layout.getDescent()));
                    strokeWidths.add(font.getSize2D() * 0.12);
                }
            }
            double total = lineHeights.stream().mapToDouble(Double::doubleValue).sum() + gap * (lineHeights.size() - 1);
            double margin = height * 0.06;
            double top = switch (position == null ? "top" : position.trim().toLowerCase()) {
                case "bottom" -> height - margin - total;
                case "center", "middle" -> (height - total) / 2.0;
                default -> margin;
            };

            // 2) 先铺一层渐变暗色衬底：模型不一定听"上方留空"，图案顶到文字下面时，衬底保证文字依然清晰
            paintScrim(g, width, height, top, total, margin, position);

            // 3) 再绘制：先画深色描边（保证任何底色上都清晰），再填白色
            double y = top;
            for (int i = 0; i < shapes.size(); i++) {
                var shape = AffineTransform.getTranslateInstance(0, y).createTransformedShape(shapes.get(i));
                g.setColor(new Color(0, 0, 0, 190));
                g.setStroke(new BasicStroke(strokeWidths.get(i).floatValue(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(shape);
                g.setColor(Color.WHITE);
                g.fill(shape);
                y += lineHeights.get(i) + gap;
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 排版与字体

    /**
     * 文字块背后的渐变衬底：靠近图片边缘一侧不透明度最高，向画面内部渐隐；居中时上下两端都渐隐。
     */
    private static void paintScrim(java.awt.Graphics2D g, int width, int height,
                                   double textTop, double textHeight, double margin, String position) {
        Color solid = new Color(8, 16, 36, 195);
        Color clear = new Color(8, 16, 36, 0);
        double pad = Math.max(margin * 1.5, height * 0.20); // 渐隐段要足够长，否则衬底下缘会露出一条硬边
        String where = position == null ? "top" : position.trim().toLowerCase();
        double y0;
        double y1;
        float[] fractions;
        Color[] colors;
        switch (where) {
            case "bottom" -> {
                y0 = Math.max(0, textTop - pad);
                y1 = height;
                fractions = new float[]{0f, clamp((textTop - margin * 0.2 - y0) / (y1 - y0)), 1f};
                colors = new Color[]{clear, solid, solid};
            }
            case "center", "middle" -> {
                y0 = Math.max(0, textTop - pad);
                y1 = Math.min(height, textTop + textHeight + pad);
                fractions = new float[]{0f, 0.25f, 0.75f, 1f};
                colors = new Color[]{clear, solid, solid, clear};
            }
            default -> {
                y0 = 0;
                y1 = Math.min(height, textTop + textHeight + pad);
                fractions = new float[]{0f, clamp((textTop + textHeight + margin * 0.2) / y1), 1f};
                colors = new Color[]{solid, solid, clear};
            }
        }
        if (y1 - y0 < 2) {
            return;
        }
        g.setPaint(new java.awt.LinearGradientPaint(0, (float) y0, 0, (float) y1, fractions, colors));
        g.fill(new java.awt.geom.Rectangle2D.Double(0, y0, width, y1 - y0));
    }

    /** LinearGradientPaint 要求分段点严格递增，夹在 (0,1) 内。 */
    private static float clamp(double fraction) {
        return (float) Math.max(0.05, Math.min(0.95, fraction));
    }

    /** 一行文字在最小字号（基础字号的一半）下仍放不下时，按字符折成多行；放得下就原样返回一行。 */
    private List<String> fit(String line, String family, int style, double base, double maxWidth, FontRenderContext frc) {
        Font minFont = new Font(family, style, 1).deriveFont((float) (base * 0.5));
        if (new TextLayout(line, minFont, frc).getAdvance() <= maxWidth) {
            return List.of(line);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            String next = current + new String(Character.toChars(cp));
            if (current.length() > 0 && new TextLayout(next, minFont, frc).getAdvance() > maxWidth) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            current.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    /** 从基础字号开始逐步缩小，直到放进可用宽度（最小为基础字号的一半）。 */
    private Font fitFont(String piece, String family, int style, double base, double maxWidth, FontRenderContext frc) {
        double size = base;
        Font font = new Font(family, style, 1).deriveFont((float) size);
        while (size > base * 0.5 && new TextLayout(piece, font, frc).getAdvance() > maxWidth) {
            size *= 0.94;
            font = font.deriveFont((float) size);
        }
        return font;
    }

    /** 挑第一个能完整显示 text 的字体族；一个都不行就明确报错（不静默画出一排方框）。 */
    String pickFont(String text) {
        Set<String> installed = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        List<String> candidates = new ArrayList<>();
        String preferred = System.getenv("PAIAGENT_OVERLAY_FONT");
        if (preferred != null && !preferred.isBlank()) {
            candidates.add(preferred.trim());
        }
        candidates.addAll(FONT_CANDIDATES);
        for (String family : candidates) {
            if (!Font.SANS_SERIF.equals(family) && !installed.contains(family)) {
                continue;
            }
            Font font = new Font(family, Font.PLAIN, 24);
            if (font.canDisplayUpTo(text) == -1) {
                return family;
            }
        }
        throw new IllegalStateException("这台机器上没有能完整显示这段文字的字体，无法把文字叠加到图片上。"
                + "请安装中文字体（如 Noto Sans CJK），或用环境变量 PAIAGENT_OVERLAY_FONT 指定字体族名");
    }

    private byte[] download(String url) throws IOException {
        try {
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    throw new IOException("下载图片失败，HTTP " + response.statusCode());
                }
                return body.readAllBytes();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载图片被中断", e);
        }
    }
}
