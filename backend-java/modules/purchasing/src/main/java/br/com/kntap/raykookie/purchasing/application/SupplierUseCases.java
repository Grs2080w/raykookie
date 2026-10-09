package br.com.kntap.raykookie.purchasing.application;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.purchasing.application.ports.Suppliers;
import br.com.kntap.raykookie.purchasing.domain.SafeUrl;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Fornecedores: sites (com busca de preço, se a plataforma for reconhecida) e lojas físicas.
 */
@Service
public class SupplierUseCases {

    /** Fornecedores. */
    private final Suppliers suppliers;

    /** Busca nas lojas Tray. */
    private final TraySearch tray;

    /** DNS, para conferir o IP dos sites. */
    private final SafeUrl.HostResolver resolver;

    /**
     * Cria os casos de uso.
     *
     * @param suppliers fornecedores
     * @param tray      busca nas lojas Tray
     * @param resolver  DNS
     */
    public SupplierUseCases(Suppliers suppliers, TraySearch tray, SafeUrl.HostResolver resolver) {
        this.suppliers = suppliers;
        this.tray = tray;
        this.resolver = resolver;
    }

    /**
     * Fornecedores por nome.
     *
     * @param owner dona
     * @return os fornecedores
     */
    public List<Map<String, Object>> list(OwnerId owner) {
        return suppliers.list(owner);
    }

    /**
     * Cria (id nulo) ou edita um fornecedor ({@code saveSupplier}).
     *
     * @param owner dona
     * @param id    fornecedor a editar, ou nulo
     * @param body  corpo JSON
     * @return a linha gravada
     * @throws InvalidInputException sem nome ou com endereço inseguro
     * @throws NotFoundException     se o fornecedor não for da dona
     */
    public Map<String, Object> save(OwnerId owner, Long id, Map<String, Object> body) {

        // ── 1. Nome e tipo.
        String name = NodeInput.cut(NodeInput.text(body.get("name")), 80);
        if (name.length() < 2) {
            throw new InvalidInputException("Dê um nome para o fornecedor.");
        }
        String kind = "loja".equals(body.get("kind")) ? "loja" : "site";

        // ── 2. Site: só endereço público; a plataforma é descoberta pela página.
        String url = "";
        String scraper = null;
        if ("site".equals(kind)) {
            url = publicOrigin(NodeInput.text(body.get("url")));
            scraper = tray.detect(url);
        }

        // ── 3. Grava (nova ou edição).
        Suppliers.Form form = new Suppliers.Form(name, kind, url, NodeInput.cut(NodeInput.text(body.get("address")),
                200),
                NodeInput.cut(NodeInput.text(body.get("notes")), 300), scraper);
        return id == null
                ? suppliers.create(owner, form)
                : suppliers.update(owner, id, form)
                        .orElseThrow(() -> new NotFoundException("Fornecedor não encontrado."));
    }

    /**
     * Origem do site, se for público.
     *
     * @param raw endereço digitado
     * @return a origem
     * @throws InvalidInputException com a mensagem do SafeUrl
     */
    private String publicOrigin(String raw) {
        try {
            return SafeUrl.origin(SafeUrl.check(raw, resolver));
        } catch (SafeUrl.UnsafeUrlException e) {
            throw new InvalidInputException(e.getMessage());
        }
    }

    /**
     * Apaga um fornecedor.
     *
     * @param owner dona
     * @param id    fornecedor
     */
    public void delete(OwnerId owner, long id) {
        suppliers.delete(owner, id);
    }
}
