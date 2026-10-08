package br.com.kntap.raykookie.storefront.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Termos de Serviço e Política de Privacidade, lidos de {@code legal/legal.json} (gerado de
 * {@code backend/shared/legal.ts}, a única fonte do texto).
 */
@Component
public class LegalDocuments {

    /** Documento → conteúdo. */
    private final Map<String, JsonNode> documents;

    /**
     * Lê os documentos empacotados.
     */
    public LegalDocuments() {
        try (InputStream in = LegalDocuments.class.getResourceAsStream("/legal/legal.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            this.documents = Map.of("termos", root.get("termos"), "privacidade", root.get("privacidade"));
        } catch (IOException e) {
            throw new UncheckedIOException("Textos legais ilegíveis", e);
        }
    }

    /**
     * Um documento ({@code isLegalId} + {@code LEGAL_DOCS}).
     *
     * @param id {@code termos} ou {@code privacidade}
     * @return o documento, ou vazio se o id não existir
     */
    public Optional<JsonNode> find(String id) {
        return Optional.ofNullable(documents.get(id));
    }
}
