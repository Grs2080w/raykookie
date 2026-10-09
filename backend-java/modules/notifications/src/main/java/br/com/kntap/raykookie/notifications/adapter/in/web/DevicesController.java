package br.com.kntap.raykookie.notifications.adapter.in.web;

import br.com.kntap.raykookie.notifications.application.DeviceUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cadastro dos celulares que recebem os avisos: {@code /api/devices} (dona) e {@code /api/cliente/devices}.
 */
@RestController
public class DevicesController {

    /** Casos de uso. */
    private final DeviceUseCases devices;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /**
     * Cria o controller.
     *
     * @param devices casos de uso
     * @param actor   quem está chamando
     */
    public DevicesController(DeviceUseCases devices, CurrentActor actor) {
        this.devices = devices;
        this.actor = actor;
    }

    /**
     * Registra (ou troca o som de) um celular da dona.
     *
     * @param body {@code token} e {@code sound}
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/devices")
    public Map<String, Object> registerOwner(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> fields = body == null ? Map.of() : body;
        devices.registerOwner(actor.requireOwner(), fields.get("token"), fields.get("sound"));
        return Map.of("ok", true);
    }

    /**
     * No logout, o celular para de receber.
     *
     * @param token token do aparelho
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/devices")
    public Map<String, Object> unregisterOwner(@RequestParam(value = "token", required = false) String token) {
        devices.unregisterOwner(actor.requireOwner(), token);
        return Map.of("ok", true);
    }

    /**
     * Registra um celular de cliente.
     *
     * @param body {@code token}
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.CUSTOMER)
    @PostMapping("/api/cliente/devices")
    public Map<String, Object> registerCustomer(@RequestBody(required = false) Map<String, Object> body) {
        devices.registerCustomer(actor.requireCustomer(), (body == null ? Map.of() : body).get("token"));
        return Map.of("ok", true);
    }

    /**
     * Ao sair da conta, o celular do cliente para de receber.
     *
     * @param token token do aparelho
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.CUSTOMER)
    @DeleteMapping("/api/cliente/devices")
    public Map<String, Object> unregisterCustomer(@RequestParam(value = "token", required = false) String token) {
        devices.unregisterCustomer(actor.requireCustomer(), token);
        return Map.of("ok", true);
    }
}
