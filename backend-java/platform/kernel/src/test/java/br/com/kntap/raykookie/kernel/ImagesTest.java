package br.com.kntap.raykookie.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Imagens: tipo pelo conteúdo e retirada de metadados do JPEG (vetores de {@code backend/tests/image.test.ts}).
 */
class ImagesTest {

    /**
     * Um segmento de JPEG.
     *
     * @param marker  marcador
     * @param payload conteúdo
     * @return os bytes
     */
    private static byte[] segment(int marker, String payload) {
        byte[] data = payload.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xff, (byte) marker, (byte) ((data.length + 2) >> 8),
            (byte) ((data.length + 2) & 0xff)});
        out.writeBytes(data);
        return out.toByteArray();
    }

    /**
     * Um JPEG com os segmentos dados.
     *
     * @param segments segmentos
     * @return os bytes
     */
    private static byte[] jpeg(byte[]... segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xd8});
        Arrays.stream(segments).forEach(out::writeBytes);
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xda, 0, 4, 0, 0, 1, 2, 3, 4, (byte) 0xff, (byte) 0xd9});
        return out.toByteArray();
    }

    /**
     * Tipo pelo conteúdo.
     */
    @Test
    @DisplayName("reconhece JPEG, PNG e WebP pelo conteúdo; recusa GIF e texto")
    void sniffs() {
        assertThat(Images.sniff(jpeg(segment(0xe0, "JFIF\0")))).contains("image/jpeg");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I'};
        assertThat(Images.sniff(png)).contains("image/png");
        assertThat(Images.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).contains("image/webp");
        assertThat(Images.sniff("GIF89a-------------".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(Images.sniff("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(Images.sniff(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})).isEmpty();
    }

    /**
     * EXIF, XMP e comentário saem; JFIF, tabelas e dados ficam (comparação de bytes).
     */
    @Test
    @DisplayName("tira EXIF com GPS, XMP e comentário, mantendo o resto byte a byte")
    void stripsJpegMetadata() {
        byte[] jfif = segment(0xe0, "JFIF\0\1\1");
        byte[] dqt = segment(0xdb, "\7\7\7\7\7\7\7\7\7\7");
        byte[] adobe = segment(0xee, "Adobe");
        byte[] stripped = Images.stripJpegMetadata(jpeg(jfif, segment(0xe1, "Exif\0\0GPS-LATITUDE-23.5"),
                segment(0xe1, "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>"), segment(0xfe, "tirada em casa"), dqt,
                new byte[] {(byte) 0xff}, adobe));
        assertThat(stripped).isEqualTo(jpeg(jfif, dqt, adobe));
    }

    /**
     * Arquivo estranho volta como veio.
     */
    @Test
    @DisplayName("estrutura inesperada volta como veio")
    void oddFilesAreKept() {
        byte[] odd = {(byte) 0xff, (byte) 0xd8, 0, 1, 2, 3, 4};
        assertThat(Images.stripJpegMetadata(odd)).isSameAs(odd);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        assertThat(Images.stripJpegMetadata(png)).isSameAs(png);
        byte[] badLength = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe1, 0x7f, 0x7f, 1};
        assertThat(Images.stripJpegMetadata(badLength)).isSameAs(badLength);
        byte[] noScan = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 2};
        assertThat(Images.stripJpegMetadata(noScan)).isSameAs(noScan);
    }

    /**
     * Fronteiras do reconhecimento: 13 bytes é o mínimo; cada assinatura exige os bytes exatos.
     */
    @Test
    @DisplayName("13 bytes é o mínimo; assinaturas incompletas não passam")
    void sniffBoundaries() {

        // ── 1. Tamanho mínimo.
        byte[] jpeg = Arrays.copyOf(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}, 13);
        assertThat(Images.sniff(jpeg)).contains("image/jpeg");
        assertThat(Images.sniff(Arrays.copyOf(jpeg, 12))).isEmpty();

        // ── 2. Um byte errado em cada assinatura.
        byte[] almostJpeg = jpeg.clone();
        almostJpeg[2] = 0x00;
        assertThat(Images.sniff(almostJpeg)).isEmpty();
        assertThat(Images.sniff(Arrays.copyOf(new byte[] {(byte) 0xff, 0x00, (byte) 0xff}, 13))).isEmpty();
        assertThat(Images.sniff(Arrays.copyOf(new byte[] {0x00, (byte) 0xd8, (byte) 0xff}, 13))).isEmpty();
        byte[] almostPng = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 0, 0, 0, 0, 13, 'I'};
        assertThat(Images.sniff(almostPng)).isEmpty();
        assertThat(Images.sniff("RIFF\0\0\0\0WEBQVP8 ".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(Images.sniff("RIFX\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
    }

    /**
     * Quais segmentos são metadados: APP1..APP15 menos o APP14, e COM; APP0 e as tabelas ficam.
     */
    @Test
    @DisplayName("descarta APP1, APP13, APP15 e COM; mantém APP0, APP14, quadro e os marcadores vizinhos")
    void metadataMarkers() {
        byte[] app0 = segment(0xe0, "JFIF");
        byte[] app14 = segment(0xee, "Adobe");
        byte[] sof = segment(0xc0, "quadro");
        byte[] stripped = Images.stripJpegMetadata(jpeg(app0, segment(0xe1, "a"), segment(0xed, "b"),
                segment(0xef, "c"), segment(0xfe, "d"), app14, sof, segment(0xdf, "e"), segment(0xf0, "f")));
        assertThat(stripped).isEqualTo(jpeg(app0, app14, sof, segment(0xdf, "e"), segment(0xf0, "f")));
    }

    /**
     * Caminho pelos segmentos: preenchimento 0xFF, fim sem dados, tamanho que chega exatamente ao fim.
     */
    @Test
    @DisplayName("pula preenchimento, para no fim da imagem e aceita segmento até o último byte")
    void segmentWalk() {

        // ── 1. Preenchimento e fim da imagem (0xD9) sem início dos dados.
        byte[] ending = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xd9, 1, 2};
        assertThat(Images.stripJpegMetadata(ending))
                .isEqualTo(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9, 1, 2});

        // ── 2. Comentário que termina exatamente no fim do arquivo: sem início dos dados, volta como veio.
        byte[] exact = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xfe, 0, 2};
        assertThat(Images.stripJpegMetadata(exact)).isSameAs(exact);
        byte[] shortLength = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe1, 0, 1, (byte) 0xff, (byte) 0xd9};
        assertThat(Images.stripJpegMetadata(shortLength)).isSameAs(shortLength);

        // ── 3. Arquivos curtos demais para ter segmento.
        byte[] tiny = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        assertThat(Images.stripJpegMetadata(tiny)).isSameAs(tiny);
        byte[] five = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9, 0};
        assertThat(Images.stripJpegMetadata(five)).isSameAs(five);
        byte[] six = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9, 0, 0};
        assertThat(Images.stripJpegMetadata(six)).isEqualTo(six).isNotSameAs(six);
    }
}
