package br.com.kntap.raykookie.payments.adapter.out.mercadopago;

import br.com.kntap.raykookie.payments.api.CardBrands;
import br.com.kntap.raykookie.payments.api.GatewayException;
import br.com.kntap.raykookie.payments.application.PaymentSettings;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Meios de cartão ativos na conta, lidos de {@code GET /v1/payment_methods} e guardados por uma hora. Se o Mercado
 * Pago não responde, vale a última lista boa (ou nenhuma: nunca se inventa bandeira).
 */
@Component
public class MercadoPagoCardBrands implements CardBrands {

    /** Log. */
    private static final Logger LOG = LoggerFactory.getLogger(MercadoPagoCardBrands.class);

    /** Tipos de cartão. */
    private static final Set<String> CARD_TYPES = Set.of("credit_card", "debit_card", "prepaid_card");

    /** Cliente HTTP. */
    private final MercadoPagoHttp http;

    /** Configuração. */
    private final PaymentSettings settings;

    /** Quanto tempo a lista vale. */
    private final Duration ttl;

    /** Última lista boa. */
    private volatile List<CardBrand> cached = List.of();

    /** Quando a lista foi lida. */
    private volatile Instant loadedAt = Instant.EPOCH;

    /**
     * Cria a fonte das bandeiras.
     *
     * @param http     chamadas HTTP ao Mercado Pago
     * @param settings configuração
     * @param ttl      validade da lista ({@code rayka.mp.brands-ttl}, padrão 1 hora)
     */
    public MercadoPagoCardBrands(MercadoPagoHttp http, PaymentSettings settings,
            @Value("${rayka.mp.brands-ttl:PT1H}") Duration ttl) {
        this.http = http;
        this.settings = settings;
        this.ttl = ttl;
    }

    /** {@inheritDoc} */
    @Override
    public List<CardBrand> list() {

        // ── 1. Sem Mercado Pago configurado não há o que mostrar; lista recente vale.
        if (!settings.configured()) {
            return List.of();
        }
        if (Instant.now().isBefore(loadedAt.plus(ttl))) {
            return cached;
        }

        // ── 2. Lê de novo; se falhar, segue com a última boa e tenta outra vez na próxima chamada depois de um
        //       minuto (para não martelar o Mercado Pago).
        try {
            cached = parse(http.call(HttpMethod.GET, "/v1/payment_methods", null, null, Map.of()));
            loadedAt = Instant.now();
        } catch (GatewayException e) {
            LOG.warn("Não consegui ler os meios de pagamento do Mercado Pago ({}); mantendo a última lista",
                    e.getMessage());
            loadedAt = Instant.now().minus(ttl).plusSeconds(60);
        }
        return cached;
    }

    /**
     * Filtra os meios de cartão ativos.
     *
     * @param methods resposta do Mercado Pago
     * @return os meios de cartão
     */
    static List<CardBrand> parse(JsonNode methods) {
        List<CardBrand> brands = new ArrayList<>();
        for (JsonNode method : methods) {
            String type = method.path("payment_type_id").asString("");
            if (CARD_TYPES.contains(type) && "active".equals(method.path("status").asString(""))) {
                brands.add(new CardBrand(method.path("id").asString(""), method.path("name").asString(""), type,
                        method.path("secure_thumbnail").asString(method.path("thumbnail").asString(""))));
            }
        }
        return List.copyOf(brands);
    }
}
