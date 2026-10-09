package br.com.kntap.raykookie.storefront.domain;

import br.com.kntap.raykookie.storefront.api.Menu;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Imagens para stories (PNG 1080×1920): o cardápio e um produto. Desenhadas com Java2D no mesmo layout de
 * {@code backend/lib/story.tsx} (cores, tamanhos e ordem dos elementos); só usam o que já é público no cardápio.
 */
public final class StoryRenderer {

    /** Largura. */
    public static final int WIDTH = 1080;

    /** Altura. */
    public static final int HEIGHT = 1920;

    /** Margem. */
    private static final int PAD = 80;

    /** Rosa da marca. */
    private static final Color PINK = new Color(0xd4, 0x2a, 0x76);

    /** Cor do texto. */
    private static final Color INK = new Color(0x2a, 0x1a, 0x22);

    /** Itens mostrados no story do cardápio. */
    private static final int MAX_ITEMS = 9;

    /** Lado da foto do produto. */
    private static final int PHOTO = 920;

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private StoryRenderer() {
    }

    /**
     * Story do cardápio: até 9 itens, com preço e peso.
     *
     * @param items itens do cardápio
     * @param host  endereço do cardápio sem "https://"
     * @return o PNG
     */
    public static byte[] menu(List<Menu.Item> items, String host) {

        // ── 1. Fundo, marca e título.
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(image);
        int y = header(g);
        y = text(g, "Cardápio", 120, Font.BOLD, INK, 1f, PAD, y + 24) + 12;
        y = text(g, "Feito à mão, sob encomenda", 38, Font.PLAIN, INK, 0.75f, PAD, y) + 56;

        // ── 2. Um cartão por item.
        List<Menu.Item> shown = items.subList(0, Math.min(items.size(), MAX_ITEMS));
        for (Menu.Item item : shown) {
            y = card(g, item, y) + 22;
        }
        if (items.size() > shown.size()) {
            text(g, "e mais " + (items.size() - shown.size()) + " no link", 34, Font.PLAIN, INK, 0.7f, PAD, y);
        }

        // ── 3. Botão no rodapé.
        button(g, host, HEIGHT - PAD - 108);
        return png(image, g);
    }

    /**
     * Story de um produto: foto (ou ilustração), nome, preço e peso.
     *
     * @param item  item do cardápio
     * @param photo foto JPEG ou PNG (nula = ilustração)
     * @param host  endereço do cardápio sem "https://"
     * @return o PNG
     */
    public static byte[] product(Menu.Item item, byte[] photo, String host) {

        // ── 1. Fundo, marca e foto (ou a ilustração).
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(image);
        int top = header(g) + 40;
        BufferedImage picture = decode(photo);
        Shape frame = new RoundRectangle2D.Double((WIDTH - PHOTO) / 2.0, top, PHOTO, PHOTO, 120, 120);
        if (picture != null) {
            drawCover(g, picture, frame);
        } else {
            g.setColor(Color.WHITE);
            g.fill(frame);
            cookie(g, WIDTH / 2, top + PHOTO / 2);
        }

        // ── 2. Nome, preço e peso.
        int y = text(g, item.name(), 84, Font.BOLD, INK, 1f, PAD, top + PHOTO + 40);
        int priceEnd = text(g, money(item.price()), 96, Font.BOLD, PINK, 1f, PAD, y + 16);
        String weight = weightLabel(item.weightG());
        if (!weight.isEmpty()) {
            int priceWidth = g.getFontMetrics(font(96, Font.BOLD)).stringWidth(money(item.price()));
            text(g, weight + " cada", 40, Font.PLAIN, INK, 0.65f, PAD + priceWidth + 28, priceEnd - 58);
        }

        // ── 3. Botão no rodapé.
        button(g, host, Math.min(priceEnd + 48, HEIGHT - PAD - 108));
        return png(image, g);
    }

    /**
     * Prepara o desenho: suavização e o fundo em degradê (160°, creme para rosa claro).
     *
     * @param image imagem
     * @return o pincel
     */
    private static Graphics2D canvas(BufferedImage image) {

        // ── 1. Desenho suave (formas, texto e foto redimensionada).
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        // ── 2. Degradê do fundo, na direção dos 160° do CSS.
        g.setPaint(new GradientPaint(168, -60, new Color(0xff, 0xf6, 0xf0), 912, 1980, new Color(0xff, 0xd9, 0xe8)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        return g;
    }

    /**
     * A marca no topo.
     *
     * @param g pincel
     * @return onde a marca termina
     */
    private static int header(Graphics2D g) {
        g.setFont(font(40, Font.BOLD).deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.15f)));
        g.setColor(PINK);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString("RAYKOOKIE", PAD, PAD + metrics.getAscent());
        return PAD + metrics.getHeight();
    }

    /**
     * Um texto numa linha, cortado com reticências se passar da largura útil.
     *
     * @param g       pincel
     * @param text    texto
     * @param size    tamanho
     * @param style   estilo
     * @param color   cor
     * @param opacity opacidade
     * @param x       esquerda
     * @param top     topo
     * @return onde o texto termina
     */
    private static int text(Graphics2D g, String text, int size, int style, Color color, float opacity, int x,
            int top) {

        // ── 1. Fonte, cor e opacidade.
        g.setFont(font(size, style));
        g.setColor(color);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));

        // ── 2. Escreve e volta à opacidade normal.
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(fit(metrics, text, WIDTH - PAD - x), x, top + metrics.getAscent());
        g.setComposite(AlphaComposite.SrcOver);
        return top + metrics.getHeight();
    }

    /**
     * Cartão branco de um item: nome e peso à esquerda, preço à direita.
     *
     * @param g    pincel
     * @param item item
     * @param top  topo
     * @return onde o cartão termina
     */
    private static int card(Graphics2D g, Menu.Item item, int top) {

        // ── 1. Altura conforme haja peso; fundo branco arredondado.
        String weight = weightLabel(item.weightG());
        int height = 60 + g.getFontMetrics(font(44, Font.BOLD)).getHeight()
                + (weight.isEmpty() ? 0 : g.getFontMetrics(font(30, Font.PLAIN)).getHeight());
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(PAD, top, WIDTH - 2.0 * PAD, height, 72, 72));

        // ── 2. Nome (até 640 px) e peso.
        g.setFont(font(44, Font.BOLD));
        g.setColor(INK);
        FontMetrics name = g.getFontMetrics();
        g.drawString(fit(name, item.name(), 640), PAD + 40, top + 30 + name.getAscent());
        if (!weight.isEmpty()) {
            text(g, weight + " cada", 30, Font.PLAIN, INK, 0.6f, PAD + 40, top + 30 + name.getHeight());
        }

        // ── 3. Preço alinhado à direita, no meio do cartão.
        g.setFont(font(52, Font.BOLD));
        g.setColor(PINK);
        FontMetrics price = g.getFontMetrics();
        String label = money(item.price());
        g.drawString(label, WIDTH - PAD - 40 - price.stringWidth(label),
                top + (height - price.getHeight()) / 2 + price.getAscent());
        return top + height;
    }

    /**
     * Botão rosa "Peça em …".
     *
     * @param g    pincel
     * @param host endereço
     * @param top  topo
     */
    private static void button(Graphics2D g, String host, int top) {

        // ── 1. Fundo rosa arredondado.
        g.setFont(font(40, Font.BOLD));
        FontMetrics metrics = g.getFontMetrics();
        int height = 60 + metrics.getHeight();
        g.setColor(PINK);
        g.fill(new RoundRectangle2D.Double(PAD, top, WIDTH - 2.0 * PAD, height, 80, 80));

        // ── 2. Texto branco centralizado.
        g.setColor(Color.WHITE);
        String label = fit(metrics, "Peça em " + host, WIDTH - 2 * PAD - 80);
        g.drawString(label, (WIDTH - metrics.stringWidth(label)) / 2, top + 30 + metrics.getAscent());
    }

    /**
     * Foto cobrindo a moldura (corta o que sobra, como {@code object-fit: cover}).
     *
     * @param g       pincel
     * @param picture foto
     * @param frame   moldura arredondada
     */
    private static void drawCover(Graphics2D g, BufferedImage picture, Shape frame) {

        // ── 1. Escala para cobrir o quadrado inteiro.
        double scale = Math.max((double) PHOTO / picture.getWidth(), (double) PHOTO / picture.getHeight());
        int width = (int) Math.ceil(picture.getWidth() * scale);
        int height = (int) Math.ceil(picture.getHeight() * scale);
        java.awt.Rectangle bounds = frame.getBounds();

        // ── 2. Desenha centralizada, recortada pela moldura arredondada.
        Shape previous = g.getClip();
        g.setClip(frame);
        g.drawImage(picture, bounds.x + (PHOTO - width) / 2, bounds.y + (PHOTO - height) / 2, width, height, null);
        g.setClip(previous);
    }

    /**
     * Ilustração de cookie no lugar da foto.
     *
     * @param g  pincel
     * @param cx centro x
     * @param cy centro y
     */
    private static void cookie(Graphics2D g, int cx, int cy) {
        g.setColor(new Color(0xd9, 0xa4, 0x41));
        g.fill(new Ellipse2D.Double(cx - 160, cy - 160, 320, 320));
        g.setColor(new Color(0x8a, 0x5a, 0x44));
        int[][] chips = {{-70, -60}, {40, -90}, {80, 20}, {-20, 40}, {-90, 70}, {50, 100}};
        for (int[] chip : chips) {
            g.fill(new Ellipse2D.Double(cx + chip[0] - 22, cy + chip[1] - 22, 44, 44));
        }
        g.setStroke(new BasicStroke(1));
    }

    /**
     * Corta o texto com reticências até caber.
     *
     * @param metrics medidas da fonte
     * @param text    texto
     * @param width   largura máxima
     * @return o texto que cabe
     */
    private static String fit(FontMetrics metrics, String text, int width) {
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && metrics.stringWidth(cut + "…") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.strip() + "…";
    }

    /**
     * Fonte sem serifa.
     *
     * @param size  tamanho
     * @param style estilo
     * @return a fonte
     */
    private static Font font(int size, int style) {
        return new Font(Font.SANS_SERIF, style, size);
    }

    /**
     * Foto JPEG ou PNG (WebP não é lido; vira a ilustração, como no Node).
     *
     * @param photo bytes (nulo = nenhuma)
     * @return a imagem, ou nula
     */
    private static BufferedImage decode(byte[] photo) {
        if (photo == null) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(photo));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Grava o PNG.
     *
     * @param image imagem
     * @param g     pincel (é liberado)
     * @return os bytes
     */
    private static byte[] png(BufferedImage image, Graphics2D g) {
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * Preço como o story do Node: "R$ 12,50" ({@code toFixed(2)}, sem separador de milhar).
     *
     * @param price preço
     * @return o texto
     */
    static String money(double price) {
        return "R$ " + new BigDecimal(price).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /**
     * Peso como o {@code weightLabel} do Node: "250 g", "1,25 kg".
     *
     * @param grams gramas (0 = vazio)
     * @return o texto
     */
    public static String weightLabel(double grams) {
        if (!(grams > 0)) {
            return "";
        }
        double value = grams >= 1000 ? grams / 1000 : grams;
        String number = br.com.kntap.raykookie.web.NodeInput.jsNumberText(Math.round(value * 100) / 100.0)
                .replace('.', ',');
        return number + (grams >= 1000 ? " kg" : " g");
    }
}
