package br.com.kntap.raykookie.customers.adapter.in.web;

import br.com.kntap.raykookie.customers.application.CustomerAccountUseCases;
import br.com.kntap.raykookie.customers.application.PrivacyRequestUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import br.com.kntap.raykookie.security.SessionCookies;
import br.com.kntap.raykookie.security.SessionTokens;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conta do cliente e direitos LGPD: {@code /api/cliente/me} e as rotas de "Meus dados".
 */
@RestController
public class CustomerAccountController {

    /** Casos de uso da conta. */
    private final CustomerAccountUseCases account;

    /** Solicitações de privacidade. */
    private final PrivacyRequestUseCases requests;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /** Cookies. */
    private final SessionCookies cookies;

    /** JSON indentado da exportação (como o {@code JSON.stringify(x, null, 2)} do Node). */
    private final JsonMapper pretty = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /**
     * Cria o controller.
     *
     * @param account  casos de uso da conta
     * @param requests solicitações de privacidade
     * @param actor    quem está chamando
     * @param cookies  cookies
     */
    public CustomerAccountController(CustomerAccountUseCases account, PrivacyRequestUseCases requests,
            CurrentActor actor, SessionCookies cookies) {
        this.account = account;
        this.requests = requests;
        this.actor = actor;
        this.cookies = cookies;
    }

    /**
     * O perfil do cliente.
     *
     * @return os campos do Node
     */
    @Access(value = AccessLevel.CUSTOMER, unauthorizedMessage = "Entre para continuar.")
    @GetMapping("/api/cliente/me")
    public Map<String, Object> me() {
        return account.profile(actor.requireCustomer());
    }

    /**
     * Corrige nome, telefone e endereço.
     *
     * @param body os campos
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.CUSTOMER)
    @PatchMapping("/api/cliente/me")
    public Map<String, Object> update(@RequestBody(required = false) Map<String, Object> body) {
        account.updateProfile(actor.requireCustomer(), CustomerAuthController.orEmpty(body));
        return Map.of("ok", true);
    }

    /**
     * Exclui (anonimiza) a conta e encerra a sessão.
     *
     * @param body {@code confirm} e {@code password}
     * @return {@code {"ok":true}} e o cookie apagado
     */
    @Access(AccessLevel.CUSTOMER)
    @DeleteMapping("/api/cliente/me")
    public ResponseEntity<Map<String, Object>> delete(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> fields = CustomerAuthController.orEmpty(body);
        account.delete(actor.requireCustomer(), fields.get("confirm"), fields.get("password"));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.clear(SessionTokens.CUSTOMER_COOKIE).toString())
                .body(Map.of("ok", true));
    }

    /**
     * "Meus dados".
     *
     * @return o JSON do Node
     */
    @Access(AccessLevel.CUSTOMER)
    @GetMapping("/api/cliente/me/dados")
    public Map<String, Object> myData() {
        return account.myData(actor.requireCustomer());
    }

    /**
     * Cópia dos dados, como anexo JSON.
     *
     * @return o arquivo
     */
    @Access(AccessLevel.CUSTOMER)
    @GetMapping("/api/cliente/me/exportar")
    public ResponseEntity<String> export() {
        String body = pretty.writeValueAsString(account.export(actor.requireCustomer()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"meus-dados-raykookie.json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /**
     * Tira o vínculo com o Google.
     *
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.CUSTOMER)
    @DeleteMapping("/api/cliente/me/google")
    public Map<String, Object> unlinkGoogle() {
        account.unlinkGoogle(actor.requireCustomer());
        return Map.of("ok", true);
    }

    /**
     * Faz uma solicitação sobre os dados.
     *
     * @param body {@code type} e {@code details}
     * @return 201 {@code {"ok":true,"id":…}}
     */
    @Access(AccessLevel.CUSTOMER)
    @PostMapping("/api/cliente/me/privacidade")
    public ResponseEntity<Map<String, Object>> privacyRequest(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> fields = CustomerAuthController.orEmpty(body);
        long id = requests.create(actor.requireCustomer(), fields.get("type"), fields.get("details"));
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("ok", true);
        created.put("id", id);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
