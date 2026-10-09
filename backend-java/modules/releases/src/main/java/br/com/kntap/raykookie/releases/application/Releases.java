package br.com.kntap.raykookie.releases.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Apps publicados ({@code readManifest}): o manifesto {@code downloads/manifest.json} e os APKs ao lado dele. Um app
 * só aparece se o arquivo existir e o nome for seguro (só o que o script de publicação gera).
 */
@Component
public class Releases {

    /** Apps conhecidos. */
    public static final List<String> APPS = List.of("client", "server");

    /** Nome de arquivo aceito. */
    private static final Pattern FILE = Pattern.compile("^[\\w.+-]+\\.apk$");

    /** JSON. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Pasta dos downloads. */
    private final Path dir;

    /**
     * Cria o leitor.
     *
     * @param dir {@code DOWNLOADS_DIR}
     */
    public Releases(@Value("${rayka.downloads-dir:downloads}") String dir) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
    }

    /**
     * Apps publicados com arquivo válido.
     *
     * @return app → versão (os campos do manifesto)
     */
    public Map<String, Map<String, Object>> manifest() {
        Map<String, Map<String, Object>> raw;
        try {
            raw = JSON.readValue(Files.readString(dir.resolve("manifest.json")), new TypeReference<>() { });
        } catch (IOException | JacksonException | ClassCastException e) {
            return Map.of();
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String app : APPS) {
            Map<String, Object> r = raw == null ? null : raw.get(app);
            String file = r == null ? "" : String.valueOf(r.get("file"));
            if (r != null && FILE.matcher(file).matches() && Files.isRegularFile(dir.resolve(file))) {
                out.put(app, new LinkedHashMap<>(r));
            }
        }
        return out;
    }

    /**
     * Versão publicada de um app.
     *
     * @param app app
     * @return a versão
     */
    public Optional<Map<String, Object>> release(String app) {
        return Optional.ofNullable(manifest().get(app));
    }

    /**
     * Arquivo de uma versão.
     *
     * @param release versão
     * @return o caminho
     */
    public Path file(Map<String, Object> release) {
        return dir.resolve(String.valueOf(release.get("file")));
    }
}
