package br.com.kntap.raykookie.kernel;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Fotos: o tipo é conferido pelo conteúdo do arquivo, não pelo que o navegador diz, e o JPEG perde os
 * metadados escondidos (porte de {@code backend/lib/image.ts}).
 */
public final class Images {

    /** Assinatura do PNG. */
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    /** Tamanho mínimo para reconhecer qualquer tipo (como no Node). */
    private static final int MIN_LENGTH = 13;

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private Images() {
    }

    /**
     * Tipo da imagem pelo conteúdo ({@code sniffImage}).
     *
     * @param bytes o arquivo
     * @return {@code image/jpeg}, {@code image/png} ou {@code image/webp}; vazio se não for nenhum
     */
    public static Optional<String> sniff(byte[] bytes) {
        if (bytes.length < MIN_LENGTH) {
            return Optional.empty();
        }
        if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return Optional.of("image/jpeg");
        }
        if (Arrays.equals(bytes, 0, PNG.length, PNG, 0, PNG.length)) {
            return Optional.of("image/png");
        }
        if (ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 12).equals("WEBP")) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    /**
     * Tira do JPEG os segmentos APP1..APP15 (menos o APP14 da Adobe, que guarda as cores) e os comentários:
     * EXIF com localização, XMP e afins ({@code stripJpegMetadata}). Estrutura inesperada volta como veio.
     *
     * @param bytes o arquivo
     * @return o JPEG sem metadados, ou o próprio arquivo
     */
    public static byte[] stripJpegMetadata(byte[] bytes) {

        // ── 1. Só JPEG.
        if (!(bytes.length > 4 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8)) {
            return bytes;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        out.write(bytes, 0, 2);

        // ── 2. Percorre os segmentos até o início dos dados da imagem.
        int i = 2;
        while (i + 4 <= bytes.length) {
            if ((bytes[i] & 0xff) != 0xff) {
                return bytes;
            }
            int marker = bytes[i + 1] & 0xff;
            if (marker == 0xff) {
                i++;
                continue;
            }
            if (marker == 0xda || marker == 0xd9) {
                out.write(bytes, i, bytes.length - i);
                return out.toByteArray();
            }

            // ── 3. Segmento com tamanho: copia ou descarta.
            int length = ((bytes[i + 2] & 0xff) << 8) | (bytes[i + 3] & 0xff);
            if (length < 2 || i + 2 + length > bytes.length) {
                return bytes;
            }
            if (!isMetadata(marker)) {
                out.write(bytes, i, 2 + length);
            }
            i += 2 + length;
        }
        return bytes;
    }

    /**
     * Diz se o segmento é de metadados descartáveis.
     *
     * @param marker marcador do segmento
     * @return verdadeiro para APP1..APP15 (menos APP14) e COM
     */
    private static boolean isMetadata(int marker) {
        return (marker >= 0xe1 && marker <= 0xef && marker != 0xee) || marker == 0xfe;
    }

    /**
     * Trecho do arquivo como texto ASCII.
     *
     * @param bytes o arquivo
     * @param from  início
     * @param to    fim (exclusivo)
     * @return o texto
     */
    private static String ascii(byte[] bytes, int from, int to) {
        return new String(bytes, from, to - from, StandardCharsets.ISO_8859_1);
    }
}
