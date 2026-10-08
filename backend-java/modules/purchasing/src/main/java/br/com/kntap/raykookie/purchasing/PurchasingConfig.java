package br.com.kntap.raykookie.purchasing;

import br.com.kntap.raykookie.purchasing.adapter.out.http.HttpSupplierSiteClient;
import br.com.kntap.raykookie.purchasing.adapter.out.http.UrlGuard;
import br.com.kntap.raykookie.purchasing.application.ports.SupplierSiteClient;
import br.com.kntap.raykookie.purchasing.domain.SafeUrl;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Peças de rede das compras: DNS, conferência dos endereços e o cliente dos sites de fornecedores.
 */
@Configuration
public class PurchasingConfig {

    /**
     * DNS do sistema.
     *
     * @return o resolvedor
     */
    @Bean
    @ConditionalOnMissingBean
    public SafeUrl.HostResolver hostResolver() {
        return host -> List.of(InetAddress.getAllByName(host));
    }

    /**
     * Conferência de cada endereço buscado: só https, site público e na porta 443.
     *
     * @param resolver DNS
     * @return a conferência
     */
    @Bean
    @ConditionalOnMissingBean
    public UrlGuard urlGuard(SafeUrl.HostResolver resolver) {
        return url -> SafeUrl.check(url, resolver);
    }

    /**
     * Cliente dos sites de fornecedores.
     *
     * @param guard conferência dos endereços
     * @return o cliente
     */
    @Bean
    @ConditionalOnMissingBean
    public SupplierSiteClient supplierSiteClient(UrlGuard guard) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new HttpSupplierSiteClient(http, guard);
    }
}
