package br.com.kntap.raykookie.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.purchasing.domain.NfceMatching;
import br.com.kntap.raykookie.purchasing.domain.NfceMatching.Ingredient;
import br.com.kntap.raykookie.purchasing.domain.NfceParser;
import br.com.kntap.raykookie.purchasing.domain.NfceParser.NfceItem;
import br.com.kntap.raykookie.purchasing.domain.NfceParser.NfceParsed;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Os casos de {@code backend/tests/nfce.test.ts}, portados um a um.
 */
class NfceTest {

    /** Chave de acesso. */
    private static final String KEY = "35260912345678000190650010000123451000123456";

    /** Link do QR code. */
    private static final String URL_QR = "https://www.nfce.fazenda.sp.gov.br/qrcode?p=" + KEY + "|2|1|1|ABCDEF";

    /** Página no layout padrão do "Consulta NFC-e". */
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
             <tr id="Item + 3"><td valign="top"><span class="txtTit">LEITE COND MO&Ccedil;A 395G</span>\
            <span class="RCod">(C&oacute;digo: 7891000)</span><br>
               <span class="Rqtd"><strong>Qtde.:</strong>1,0000</span><span class="RUN"><strong>UN: </strong>UN</span>\
            <span class="RvlUnit"><strong>Vl. Unit.:</strong> 5,4900</span></td>
               <td align="right"><span class="valor">5,49</span></td></tr>
            </table>
            <div id="totalNota"><label>Valor a pagar R$:</label><span class="totalNumb txtMax">22,77</span></div>
            <ul><li><strong>Emiss&atilde;o:</strong> 12/09/2026 10:32:11 - Via Consumidor</li></ul>
            </body></html>""";

    /** Outro layout: só o texto. */
    private static final String PLAIN = """
            <div><h4>MERCADINHO X</h4><table><tr><td>MANTEIGA COM SAL 200G (Código: 55) Qtde.: 3 UN: UN \
            Vl. Unit.: 9,90 Vl. Total 29,70</td></tr>
            <tr><td>CHOCOLATE EM PO 50% 200G Qtde.: 1 UN: UN Vl. Unit.: 12,50 Vl. Total 12,50</td></tr></table>\
            <p>Valor total R$ 42,20</p><p>Emissão: 01/10/2026 09:00:00</p></div>""";

    /** Ingredientes do estoque. */
    private static final List<Ingredient> INGS = List.of(new Ingredient(1, "Leite condensado", "g", 395, 5),
            new Ingredient(2, "Açúcar", "g", 1000, 4), new Ingredient(3, "Manteiga", "g", 200, 9),
            new Ingredient(4, "Leite", "ml", 1000, 5));

    /**
     * Item de teste.
     *
     * @param name      nome
     * @param qty       quantidade
     * @param unit      unidade
     * @param unitPrice preço unitário
     * @return o item
     */
    private static NfceItem item(String name, double qty, String unit, double unitPrice) {
        return new NfceItem(name, "", qty, unit, unitPrice, qty * unitPrice);
    }

    /**
     * Layout padrão.
     */
    @Test
    @DisplayName("lê o layout padrão do portal: nome, quantidade, unidade, preços, loja, data e total")
    void portal() {
        NfceParsed nota = NfceParser.parse(PORTAL, URL_QR).orElseThrow();
        assertThat(nota.key()).isEqualTo(KEY);
        assertThat(nota.store()).isEqualTo("SUPERMERCADO BOM PRECO LTDA");
        assertThat(nota.date()).isEqualTo("2026-09-12");
        assertThat(nota.total()).isEqualTo(22.77);
        assertThat(nota.items()).containsExactly(
                new NfceItem("LEITE COND MOÇA 395G", "7891000", 3, "UN", 5.49, 16.47),
                new NfceItem("AÇUCAR REFINADO", "12", 1.5, "KG", 4.2, 6.3));
    }

    /**
     * Plano B, chave por impressão digital e página sem itens.
     */
    @Test
    @DisplayName("plano B para outro layout; sem chave usa impressão digital; sem itens é vazio")
    void plainLayout() {
        NfceParsed nota = NfceParser.parse(PLAIN, "https://x.fazenda.gov.br/n?p=1").orElseThrow();
        assertThat(nota.items()).extracting(NfceItem::name, NfceItem::qty, NfceItem::unitPrice, NfceItem::total)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("MANTEIGA COM SAL 200G", 3.0, 9.9, 29.7),
                        org.assertj.core.groups.Tuple.tuple("CHOCOLATE EM PO 50% 200G", 1.0, 12.5, 12.5));
        assertThat(nota.items().get(0).code()).isEqualTo("55");
        assertThat(nota.total()).isEqualTo(42.2);
        assertThat(nota.key()).startsWith("h:").hasSize(42);
        assertThat(NfceParser.parse(PLAIN, "").orElseThrow().key()).isEqualTo(NfceParser.parse(PLAIN, "")
                .orElseThrow().key());
        assertThat(NfceParser.parse("<html><body>Informe o captcha para continuar</body></html>", "")).isEmpty();
    }

    /**
     * A impressão digital é a mesma do Node (gerada com {@code parseNfceHtml} do TypeScript), para a trava de
     * nota repetida valer para as notas lançadas antes do corte.
     */
    @Test
    @DisplayName("impressão digital igual à do Node")
    void fingerprintMatchesNode() {
        assertThat(NfceParser.parse(PLAIN, "").orElseThrow().key())
                .isEqualTo("h:20889f7774a27d6c7ea40fd20b5ca9ed28f67d80");
    }

    /**
     * Utilitários.
     */
    @Test
    @DisplayName("brNumber, chave do QR code, links .gov.br, tamanho no nome e palavras")
    void utilities() {
        assertThat(NfceParser.brNumber("4,9900")).isEqualTo(4.99);
        assertThat(NfceParser.brNumber("1.234,56")).isEqualTo(1234.56);
        assertThat(NfceParser.brNumber("1.000.000")).isEqualTo(1_000_000);
        assertThat(NfceParser.brNumber("R$ 7,5")).isEqualTo(7.5);
        assertThat(NfceParser.brNumber("")).isNaN();
        assertThat(NfceParser.accessKeyFromText(URL_QR)).contains(KEY);
        assertThat(NfceParser.accessKeyFromText("http://x.gov.br/?p=" + KEY + "%7C2")).contains(KEY);
        assertThat(NfceParser.accessKeyFromText("https://x.gov.br/?p=123")).isEmpty();
        assertThat(NfceParser.accessKeyFromText("%E0%A4%A " + KEY)).contains(KEY);
        assertThat(NfceParser.isNfceUrl(URL_QR)).isTrue();
        assertThat(NfceParser.isNfceUrl("http://www.fazenda.pr.gov.br/nfce/qrcode?p=1")).isTrue();
        assertThat(NfceParser.isNfceUrl("https://gov.br.evil.com/x")).isFalse();
        assertThat(NfceParser.isNfceUrl("https://evilgov.br/x")).isFalse();
        assertThat(NfceParser.isNfceUrl("file:///etc/passwd")).isFalse();
        assertThat(NfceParser.isNfceUrl("nao e url")).isFalse();
        assertThat(NfceParser.decodeEntities("&#65;&#x42;&zzz;&#0;")).isEqualTo("AB&zzz;&#0;");
    }

    /**
     * Tamanho no nome e palavras.
     */
    @Test
    @DisplayName("tamanho escrito no nome e palavras do nome")
    void sizesAndTokens() {
        assertThat(NfceMatching.sizeInName("LEITE COND 395G")).contains(new NfceMatching.Size("g", 395));
        assertThat(NfceMatching.sizeInName("ACUCAR 1,5 KG")).contains(new NfceMatching.Size("g", 1500));
        assertThat(NfceMatching.sizeInName("LEITE 1L")).contains(new NfceMatching.Size("ml", 1000));
        assertThat(NfceMatching.sizeInName("SUCO 200ML")).contains(new NfceMatching.Size("ml", 200));
        assertThat(NfceMatching.sizeInName("OVOS BRANCOS")).isEmpty();
        assertThat(NfceMatching.nameTokens("LEITE COND MOÇA 395G")).containsExactly("leite", "condensado", "moca");
    }

    /**
     * Casamento com o estoque.
     */
    @Test
    @DisplayName("casa com abreviações, prefere o mais específico e não confunde produtos diferentes")
    void matching() {
        assertThat(match("LEITE COND MOÇA 395G", INGS)).isEqualTo(new NfceMatching.Match(1, "Leite condensado", true));
        assertThat(match("ACUCAR 1KG", INGS)).isEqualTo(new NfceMatching.Match(2, "Açúcar", true));
        assertThat(match("MANTEIGA 200G", INGS)).isEqualTo(new NfceMatching.Match(3, "Manteiga", true));
        assertThat(match("MANTEIGA COM SAL 200G", INGS).exact()).isFalse();
        assertThat(match("LEITE CONDENSADO PIRACANJUBA", INGS).ingredientId()).isEqualTo(1);
        assertThat(match("ACUCAR REFINADO UNIAO 1KG", INGS)).isEqualTo(new NfceMatching.Match(2, "Açúcar", false));
        assertThat(match("ACUCAR REFINADO", INGS)).isEqualTo(new NfceMatching.Match(2, "Açúcar", false));
        assertThat(match("CREME DE LEITE 200G", List.of(INGS.get(3)))).isEqualTo(new NfceMatching.Match(4, "Leite",
                false));
        assertThat(NfceMatching.matchIngredient("CREME DE LEITE 200G", List.of(INGS.get(0)))).isEmpty();
        assertThat(match("LEITE CONDENSADO", List.of(INGS.get(3))).exact()).isFalse();
        assertThat(match("LEITE EM PO INTEGRAL ITAMBE 400G", List.of(INGS.get(3))))
                .isEqualTo(new NfceMatching.Match(4, "Leite", false));
        assertThat(NfceMatching.matchIngredient("DETERGENTE YPE 500ML", INGS)).isEmpty();
        assertThat(NfceMatching.matchIngredient("PAPEL TOALHA", List.of())).isEmpty();
        assertThat(NfceMatching.matchIngredient("500G", INGS)).isEmpty();
    }

    /**
     * Casamento que precisa existir.
     *
     * @param name nome do item
     * @param list estoque
     * @return o casamento
     */
    private static NfceMatching.Match match(String name, List<Ingredient> list) {
        return NfceMatching.matchIngredient(name, list).orElseThrow();
    }

    /**
     * Quanto entra no estoque e a sugestão de cadastro.
     */
    @Test
    @DisplayName("planAdd converte embalagens e granel; planCreate deduz unidade e tamanho")
    void plans() {
        assertThat(NfceMatching.planAdd(item("LEITE COND 395G", 3, "UN", 5.49), INGS.get(0)))
                .isEqualTo(new NfceMatching.AddPlan(3, 5.49, "3 embalagem(ns)"));
        assertThat(NfceMatching.planAdd(item("ACUCAR 500G", 2, "UN", 3), INGS.get(1)))
                .isEqualTo(new NfceMatching.AddPlan(1, 6, "2 × 500 g = 1 embalagem(ns) de 1000 g"));
        assertThat(NfceMatching.planAdd(item("ACUCAR REFINADO", 1.5, "KG", 4.2), INGS.get(1)))
                .isEqualTo(new NfceMatching.AddPlan(1.5, 4.2, "1500 g ≈ 1.5 embalagem(ns)"));
        assertThat(NfceMatching.planAdd(item("ACUCAR REFINADO", 1.5, "KG", 4.2), new Ingredient(9, "x", "g", 500, 2)))
                .isEqualTo(new NfceMatching.AddPlan(3, 2.1, "1500 g ≈ 3 embalagem(ns)"));
        assertThat(NfceMatching.planCreate(item("CREME DE LEITE 200G", 2, "UN", 3.29)))
                .isEqualTo(new NfceMatching.CreatePlan("Creme de leite 200g", "g", 200, 3.29, 2));
        assertThat(NfceMatching.planCreate(item("FARINHA DE TRIGO", 2, "KG", 6)))
                .isEqualTo(new NfceMatching.CreatePlan("Farinha de trigo", "g", 1000, 6, 2));
        assertThat(NfceMatching.planCreate(item("OVOS BRANCOS", 1, "UN", 12)))
                .isEqualTo(new NfceMatching.CreatePlan("Ovos brancos", "un", 1, 12, 1));
        assertThat(NfceMatching.planCreate(item("LEITE", 2, "L", 5)).unit()).isEqualTo("ml");
    }
}
