package br.com.kntap.raykookie.contract.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ChangedFromNode;
import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Leitura e lançamento de nota fiscal (NFC-e): {@code /api/nota/ler} e {@code /api/nota/importar}
 * (Tarefa 4.5).
 */
class NotaContractIT extends ContractTestBase {

    /** Página no layout padrão do "Consulta NFC-e" (a mesma de {@code backend/tests/nfce.test.ts}). */
    private static final String PORTAL = """
            <html><body>
            <div id="u20" class="txtCenter"><div id="u20" class="txtTopo">SUPERMERCADO BOM PRECO LTDA</div>\
            <div class="text">CNPJ: 12.345.678/0001-90</div></div>
            <table id="tabResult">
             <tr id="Item + 1"><td valign="top"><span class="txtTit">LEITE COND MO&Ccedil;A 395G</span>\
            <span class="RCod">&nbsp;(C&oacute;digo: 7891000)</span><br>
               <span class="Rqtd"><strong>Qtde.:</strong>2,0000</span><span class="RUN"><strong>UN: </strong>UN</span>\
            <span class="RvlUnit"><strong>Vl. Unit.:</strong>&nbsp;&nbsp;5,4900</span></td>
               <td align="right" valign="top" class="txtTit noWrap">Vl. Total<br><span class="valor">10,98</span></td></tr>
             <tr id="Item + 2"><td valign="top"><span class="txtTit">A&Ccedil;UCAR REFINADO</span>\
            <span class="RCod">(C&oacute;digo: 12)</span><br>
               <span class="Rqtd"><strong>Qtde.:</strong>1,5000</span><span class="RUN"><strong>UN: </strong>KG</span>\
            <span class="RvlUnit"><strong>Vl. Unit.:</strong> 4,2000</span></td>
               <td align="right"><span class="valor">6,30</span></td></tr>
            </table>
            <div id="totalNota"><label>Valor a pagar R$:</label><span class="totalNumb txtMax">17,28</span></div>
            <ul><li><strong>Emiss&atilde;o:</strong> 12/09/2026 10:32:11 - Via Consumidor</li></ul>
            </body></html>""";

    /** Sessão da dona. */
    private ContractSeed.Session owner;

    /**
     * Abre a sessão da dona.
     */
    @BeforeEach
    void openSession() {
        owner = seed.owner(target.shopOwner());
    }

    /**
     * Requisição JSON como a dona.
     *
     * @param body corpo
     * @return a requisição
     */
    private RequestSpecification asOwner(Object body) {
        return api().header("Authorization", owner.bearer()).contentType(ContentType.JSON).body(body);
    }

    /**
     * Um link de QR code com uma chave de 44 dígitos nova.
     *
     * @return o link
     */
    private static String newQrUrl() {
        String digits = UUID.randomUUID().toString().replaceAll("\\D", "") + "00000000000000000000000000000000000000";
        return "https://www.nfce.fazenda.sp.gov.br/qrcode?p=" + digits.substring(0, 44) + "|2|1|1|ABC";
    }

    /**
     * Lê a nota: itens, loja, data, total e o casamento com o estoque.
     */
    @Test
    @DisplayName("ler: itens, loja, data, total e sugestão de cadastro")
    void reads() {

        // ── 1. Lê a nota.
        String url = newQrUrl();
        Response read = asOwner(Map.of("url", url, "html", PORTAL)).post("/api/nota/ler");
        assertJson(read, 200, "nota-read");
        JsonPath json = read.jsonPath();

        // ── 2. O que o Node devolve.
        assertThat(json.getString("key")).isEqualTo(url.substring(url.indexOf("p=") + 2, url.indexOf('|')));
        assertThat(json.getString("store")).isEqualTo("SUPERMERCADO BOM PRECO LTDA");
        assertThat(json.getString("date")).isEqualTo("2026-09-12");
        assertThat(json.getDouble("total")).isEqualTo(17.28);
        assertThat(json.getString("already_imported")).isNull();
        assertThat(json.getString("items[0].name")).isEqualTo("LEITE COND MOÇA 395G");
        assertThat(json.getString("items[0].create.name")).isEqualTo("Leite cond moça 395g");
        assertThat(json.getDouble("items[1].qty")).isEqualTo(1.5);
        assertThat(json.getString("items[1].unit")).isEqualTo("KG");
    }

    /**
     * Validações da leitura.
     */
    @Test
    @DisplayName("ler: link que não é .gov.br, página curta, grande demais e sem itens")
    void readValidation() {
        assertError(asOwner(Map.of("url", "https://evil.com/x", "html", PORTAL)).post("/api/nota/ler"), 400,
                "Esse QR code não é de uma nota fiscal (o link precisa ser de um portal .gov.br).");
        assertError(asOwner(Map.of("html", "<html></html>")).post("/api/nota/ler"), 400,
                "Não consegui abrir a página da nota. Confira a internet e tente de novo.");
        assertError(asOwner(Map.of("html", "x".repeat(3_000_001))).post("/api/nota/ler"), 400,
                "A página da nota veio grande demais para ler.");
        Response noItems = asOwner(Map.of("html", "<html><body>" + "Informe o captcha ".repeat(20)
                + "</body></html>")).post("/api/nota/ler");
        assertThat(noItems.statusCode()).isEqualTo(422);
    }

    /**
     * Lança a nota: soma num ingrediente, cria outro e trava a repetição.
     */
    @Test
    @DisplayName("importar: soma, cria, lembra o apelido e não lança a mesma nota duas vezes")
    void imports() {

        // ── 1. Um ingrediente existente e a nota com um item para somar e outro para criar.
        long milk = seed.insert("INSERT INTO ingredients (user_id, name, unit, package_size, package_cost, stock_qty) "
                + "VALUES (?, ?, 'g', 395, 5, 1) RETURNING id", owner.id(), "Leite condensado " + UUID.randomUUID());
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("key", key, "store", "Mercado", "date", "2026-09-12", "total", "17,28",
                "items", List.of(
                        Map.of("action", "add", "ingredient_id", milk, "add_qty", 2, "package_cost", "5,49",
                                "item_name", "LEITE COND MOÇA 395G"),
                        Map.of("action", "create", "name", "Açúcar da nota " + key.substring(0, 6), "unit", "g",
                                "package_size", 1000, "package_cost", 4.2, "stock_qty", 1.5)));

        // ── 2. Lança e confere o estoque, o preço, o apelido e o ingrediente novo.
        assertJson(asOwner(body).post("/api/nota/importar"), 201, "nota-imported");
        assertThat(seed.value("SELECT stock_qty::text FROM ingredients WHERE id = ?", milk)).isEqualTo("3.000");
        assertThat(seed.value("SELECT package_cost::text FROM ingredients WHERE id = ?", milk)).isEqualTo("5.49");
        assertThat(seed.value("SELECT item_key FROM receipt_aliases WHERE ingredient_id = ?", milk))
                .isEqualTo("leite condensado moca");
        assertThat(seed.value("SELECT notes FROM ingredients WHERE user_id = ? AND name = ?", owner.id(),
                "Açúcar da nota " + key.substring(0, 6))).isEqualTo("Criado pela leitura de nota fiscal.");
        assertThat(seed.value("SELECT count(*)::int FROM price_quotes WHERE ingredient_id = ? AND source = 'nota'",
                milk)).isEqualTo(1);
    }

    /**
     * A mesma nota não é lançada de novo (no Node, 400; no Java, 409 de conflito).
     */
    @Test
    @ChangedFromNode("Tarefa 4.5: nota repetida é conflito")
    @DisplayName("importar a mesma nota duas vezes: 409")
    void duplicateIsConflict() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("key", key, "items", List.of(Map.of("action", "create", "name",
                "Item " + key.substring(0, 6), "unit", "un", "package_size", 1, "package_cost", 1)));
        asOwner(body).post("/api/nota/importar").then().statusCode(201);
        assertError(asOwner(body).post("/api/nota/importar"), 409, "Essa nota já foi lançada no estoque.");
    }

    /**
     * Validações do lançamento; um erro desfaz a nota inteira.
     */
    @Test
    @DisplayName("importar: sem chave, sem itens, item desconhecido e ingrediente sumido (desfaz tudo)")
    void importValidation() {
        assertError(asOwner(Map.of("items", List.of())).post("/api/nota/importar"), 400,
                "Nota sem identificação. Leia o QR code de novo.");
        assertError(asOwner(Map.of("key", "k")).post("/api/nota/importar"), 400, "Nenhum item para lançar.");
        String key = UUID.randomUUID().toString();
        assertError(asOwner(Map.of("key", key, "items", List.of(Map.of("action", "x")))).post("/api/nota/importar"),
                400, "Item da nota em formato desconhecido.");
        assertError(asOwner(Map.of("key", key, "items", List.of(Map.of("action", "add", "ingredient_id", 999_999_999,
                "add_qty", 1)))).post("/api/nota/importar"), 400,
                "Um dos ingredientes escolhidos não existe mais. Leia a nota de novo.");
        assertThat(seed.value("SELECT count(*)::int FROM receipt_imports WHERE access_key = ?", key)).isEqualTo(0);
    }

    /**
     * Sem sessão: 401.
     */
    @Test
    @DisplayName("sem sessão: 401")
    void withoutSession() {
        assertError(api().contentType(ContentType.JSON).body("{}").post("/api/nota/ler"), 401,
                "Sessão expirada. Entre novamente.");
        assertError(api().contentType(ContentType.JSON).body("{}").post("/api/nota/importar"), 401,
                "Sessão expirada. Entre novamente.");
    }
}
