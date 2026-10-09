package br.com.kntap.raykookie.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cliente HTTP/1.1 cru, por socket: deixa o teste mentir no {@code Content-Length}, mandar o corpo em
 * partes e ler a resposta enquanto ainda envia — coisas que os clientes HTTP prontos não permitem.
 */
final class RawHttp {

    /**
     * Sem instâncias.
     */
    private RawHttp() {
    }

    /**
     * Manda o cabeçalho, escreve o corpo numa thread à parte e lê a resposta ao mesmo tempo.
     *
     * @param port    porta do servidor
     * @param head    linha de pedido e cabeçalhos, sem a linha em branco final
     * @param body    o que escreve o corpo (pode parar no meio com erro: o servidor pode fechar antes)
     * @return a resposta
     * @throws IOException se a conexão ou a leitura falhar
     */
    static Response exchange(int port, String head, BodyWriter body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            long start = System.nanoTime();
            out.write((head + "\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            Thread writer = Thread.ofVirtual().start(() -> writeQuietly(body, out));
            Response response = read(socket.getInputStream(), start);
            writer.interrupt();
            return response;
        }
    }

    /**
     * Escreve o corpo, ignorando a conexão fechada pelo servidor no meio do envio.
     *
     * @param body o que escreve o corpo
     * @param out  saída do socket
     */
    private static void writeQuietly(BodyWriter body, OutputStream out) {
        try {
            body.write(out);
            out.flush();
        } catch (IOException closedByServer) {
            // O servidor recusou e fechou a conexão: é exatamente o que alguns testes esperam.
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Corpo em partes ({@code Transfer-Encoding: chunked}) de um certo tamanho.
     *
     * @param totalBytes tamanho total
     * @return o escritor do corpo
     */
    static BodyWriter chunked(long totalBytes) {
        return chunked(totalBytes, (byte) 0);
    }

    /**
     * Corpo em partes que é o começo de um JSON válido e longo ({@code {"a":"xxxx…}): o leitor de JSON
     * continua lendo até o fim, em vez de parar no primeiro byte inválido.
     *
     * @param totalBytes tamanho total
     * @return o escritor do corpo
     */
    static BodyWriter chunkedJson(long totalBytes) {
        BodyWriter filler = chunked(totalBytes - 6, (byte) 'x');
        return out -> {
            byte[] start = "{\"a\":\"".getBytes(StandardCharsets.ISO_8859_1);
            out.write((Integer.toHexString(start.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(start);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            filler.write(out);
        };
    }

    /**
     * Corpo em partes de um certo tamanho, preenchido com um byte.
     *
     * @param totalBytes tamanho total
     * @param fill       byte de preenchimento
     * @return o escritor do corpo
     */
    private static BodyWriter chunked(long totalBytes, byte fill) {
        return out -> {
            byte[] chunk = new byte[64 * 1024];
            java.util.Arrays.fill(chunk, fill);
            long left = totalBytes;
            while (left > 0) {
                int size = (int) Math.min(chunk.length, left);
                out.write((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(chunk, 0, size);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                left -= size;
            }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        };
    }

    /**
     * Corpo comum de um certo tamanho.
     *
     * @param totalBytes tamanho
     * @return o escritor do corpo
     */
    static BodyWriter fixed(int totalBytes) {
        return out -> out.write(new byte[totalBytes]);
    }

    /**
     * Lê uma resposta HTTP/1.1 (corpo por {@code Content-Length} ou em partes).
     *
     * @param in    entrada do socket
     * @param start instante do envio, para medir o tempo até a resposta
     * @return a resposta
     * @throws IOException se a leitura falhar
     */
    private static Response read(InputStream in, long start) throws IOException {
        String statusLine = line(in);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String header = line(in); !header.isEmpty(); header = line(in)) {
            int colon = header.indexOf(':');
            headers.put(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
        }
        byte[] body = readBody(in, headers);
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        return new Response(status, headers, new String(body, StandardCharsets.UTF_8), elapsedMillis);
    }

    /**
     * Lê o corpo da resposta.
     *
     * @param in      entrada do socket
     * @param headers cabeçalhos da resposta
     * @return o corpo
     * @throws IOException se a leitura falhar
     */
    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String encoding = headers.getOrDefault("Transfer-Encoding", "").toLowerCase(Locale.ROOT);
        if (!encoding.contains("chunked")) {
            return in.readNBytes(Integer.parseInt(headers.getOrDefault("Content-Length", "0")));
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int size = Integer.parseInt(line(in).trim(), 16); size > 0; size = Integer.parseInt(line(in).trim(), 16)) {
            body.write(in.readNBytes(size));
            line(in);
        }
        return body.toByteArray();
    }

    /**
     * Lê uma linha terminada em CRLF.
     *
     * @param in entrada
     * @return a linha sem o CRLF
     * @throws IOException se a conexão fechar antes do fim da linha
     */
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int c = in.read(); c != '\n'; c = in.read()) {
            if (c < 0) {
                throw new IOException("conexão fechada no meio da resposta");
            }
            if (c != '\r') {
                line.write(c);
            }
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    /**
     * Escreve o corpo de uma requisição.
     */
    @FunctionalInterface
    interface BodyWriter {

        /**
         * Escreve o corpo.
         *
         * @param out saída do socket
         * @throws IOException se a conexão cair
         */
        void write(OutputStream out) throws IOException;
    }

    /**
     * Resposta lida.
     *
     * @param status        status HTTP
     * @param headers       cabeçalhos
     * @param body          corpo em UTF-8
     * @param elapsedMillis tempo do envio do cabeçalho até a linha de status
     */
    record Response(int status, Map<String, String> headers, String body, long elapsedMillis) {
    }
}
