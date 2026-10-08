package br.com.kntap.raykookie.releases.adapter.in.web;

import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.releases.application.Releases;
import br.com.kntap.raykookie.releases.domain.ByteRange;
import br.com.kntap.raykookie.releases.domain.DownloadLinkSigner;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import br.com.kntap.raykookie.security.ShopOwner;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Downloads dos apps Android: a lista, o APK (com {@code Range}) e o link assinado de 5 minutos. O app de gestão
 * ({@code server}) aparece e baixa só para a dona (G01) ou com o link assinado.
 */
@RestController
public class DownloadsController {

    /** Sem sessão da dona para o app de gestão. */
    static final String LOGIN = "Entre na sua conta para baixar o app de gestão.";

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(DownloadsController.class);

    /** Bloco de cópia. */
    private static final int BUFFER = 64 * 1024;

    /** Apps publicados. */
    private final Releases releases;

    /** Link assinado. */
    private final DownloadLinkSigner signer;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /** A dona. */
    private final ShopOwner shopOwner;

    /** Relógio. */
    private final Clock clock;

    /**
     * Cria o controller.
     *
     * @param releases  apps publicados
     * @param secret    {@code DOWNLOAD_SECRET}
     * @param actor     quem está chamando
     * @param shopOwner a dona
     * @param clock     relógio
     */
    public DownloadsController(Releases releases, @Value("${rayka.download-secret}") String secret,
            CurrentActor actor, ShopOwner shopOwner, Clock clock) {
        this.releases = releases;
        this.signer = new DownloadLinkSigner(secret);
        this.actor = actor;
        this.shopOwner = shopOwner;
        this.clock = clock;
    }

    /**
     * Diz se quem chama é a dona da loja.
     *
     * @return verdadeiro se for
     */
    private boolean isShopOwner() {
        return actor.ownerIfAny().filter(shopOwner::is).isPresent();
    }

    /**
     * Versões publicadas: o app do cliente é público; o de gestão só aparece para a dona.
     *
     * @return app → versão com {@code url}
     */
    @Access(AccessLevel.OWNER_OR_PUBLIC)
    @GetMapping("/api/downloads")
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Map<String, Object>> m = releases.manifest();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String app : Releases.APPS) {
            if (m.containsKey(app) && ("client".equals(app) || isShopOwner())) {
                Map<String, Object> r = new LinkedHashMap<>(m.get(app));
                r.put("url", "/api/downloads/" + app);
                out.put(app, r);
            }
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(out);
    }

    /**
     * Baixa o APK (aceita {@code Range} para retomar downloads interrompidos).
     *
     * @param app      app
     * @param token    link assinado ({@code t})
     * @param range    cabeçalho {@code Range}
     * @param response resposta
     * @throws IOException se o arquivo não puder ser lido
     */
    @Access(AccessLevel.OWNER_OR_PUBLIC)
    @GetMapping("/api/downloads/{app}")
    public void download(@PathVariable String app, @RequestParam(name = "t", required = false) String token,
            @RequestHeader(name = "Range", required = false) String range, HttpServletResponse response)
            throws IOException {

        // ── 1. App conhecido, permissão e versão publicada.
        if (!Releases.APPS.contains(app)) {
            throw new NotFoundException("App não encontrado.");
        }
        if ("server".equals(app) && !isShopOwner() && !signer.verify("server", token, clock.instant())) {
            throw new DomainException(LOGIN, 401);
        }
        Map<String, Object> r = releases.release(app)
                .orElseThrow(() -> new NotFoundException("Esse app ainda não foi publicado."));
        Path file = releases.file(r);
        long size = Files.size(file);

        // ── 2. Cabeçalhos e o trecho pedido.
        response.setHeader("Content-Type", "application/vnd.android.package-archive");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + r.get("file") + "\"");
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("ETag", "\"" + r.get("sha256") + "\"");
        response.setHeader("X-App-Version", r.get("version") + "+" + r.get("build"));
        ByteRange part = ByteRange.of(range, size).orElse(null);
        if (part == null) {
            response.setStatus(416);
            response.setHeader("Content-Range", "bytes */" + size);
            return;
        }
        if (part.length() != size) {
            response.setStatus(206);
            response.setHeader("Content-Range", "bytes " + part.start() + "-" + part.end() + "/" + size);
        }
        response.setContentLengthLong(part.length());

        // ── 3. Copia; cliente que fecha a conexão no meio não é erro (G30).
        stream(file, part, response);
    }

    /**
     * Copia o trecho do arquivo para a resposta.
     *
     * @param file     arquivo
     * @param part     trecho
     * @param response resposta
     * @throws IOException se o arquivo não puder ser lido
     */
    private static void stream(Path file, ByteRange part, HttpServletResponse response) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(part.start());
            long left = part.length();
            byte[] buffer = new byte[BUFFER];
            OutputStream out;
            try {
                out = response.getOutputStream();
                while (left > 0) {
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (n < 0) {
                        break;
                    }
                    out.write(buffer, 0, n);
                    left -= n;
                }
                out.flush();
            } catch (IOException aborted) {
                LOG.debug("Download interrompido pelo cliente", aborted);
            }
        }
    }

    /**
     * Link de download de 5 minutos (o app abre o navegador, que não tem a sessão).
     *
     * @param app app
     * @return {@code url}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/downloads/{app}/link")
    public Map<String, Object> link(@PathVariable String app) {
        if (!Releases.APPS.contains(app)) {
            throw new NotFoundException("App não encontrado.");
        }
        if ("client".equals(app)) {
            return Map.of("url", "/api/downloads/client");
        }
        OwnerId owner = actor.requireOwner();
        if (!shopOwner.is(owner)) {
            throw new DomainException(LOGIN, 401);
        }
        long exp = clock.instant().getEpochSecond() + DownloadLinkSigner.TTL_SECONDS;
        return Map.of("url", "/api/downloads/server?t=" + java.net.URLEncoder.encode(signer.sign("server", exp),
                java.nio.charset.StandardCharsets.UTF_8));
    }
}
