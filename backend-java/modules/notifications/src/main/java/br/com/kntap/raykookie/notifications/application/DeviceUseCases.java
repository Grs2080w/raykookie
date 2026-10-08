package br.com.kntap.raykookie.notifications.application;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.notifications.application.ports.Devices;
import br.com.kntap.raykookie.notifications.domain.OrderSound;
import org.springframework.stereotype.Service;

/**
 * Cadastro dos aparelhos que recebem os avisos, com as validações do Node.
 */
@Service
public class DeviceUseCases {

    /** Mensagem de token inválido. */
    static final String INVALID_TOKEN = "Token do aparelho inválido.";

    /** Aparelhos. */
    private final Devices devices;

    /**
     * Cria os casos de uso.
     *
     * @param devices aparelhos
     */
    public DeviceUseCases(Devices devices) {
        this.devices = devices;
    }

    /**
     * Cadastra um celular da dona (ou troca o som dele).
     *
     * @param owner dona
     * @param token token recebido
     * @param sound som recebido (ausente = mantém)
     */
    public void registerOwner(OwnerId owner, Object token, Object sound) {
        String valid = requireToken(clean(token));
        if (sound != null && !OrderSound.isValid(sound)) {
            throw new InvalidInputException("Som desconhecido.");
        }
        devices.registerOwner(owner, valid, sound == null ? null : String.valueOf(sound));
    }

    /**
     * Descadastra um celular da dona (no logout).
     *
     * @param owner dona
     * @param token token da consulta
     */
    public void unregisterOwner(OwnerId owner, String token) {
        devices.unregisterOwner(owner, requireToken(clean(token)));
    }

    /**
     * Cadastra um celular de cliente.
     *
     * @param customer cliente
     * @param token    token recebido
     */
    public void registerCustomer(CustomerId customer, Object token) {
        devices.registerCustomer(customer, requireToken(token == null ? "" : String.valueOf(token).strip()));
    }

    /**
     * Descadastra um celular de cliente.
     *
     * @param customer cliente
     * @param token    token da consulta
     */
    public void unregisterCustomer(CustomerId customer, String token) {
        devices.unregisterCustomer(customer, requireToken(token == null ? "" : token.strip()));
    }

    /**
     * Token de 20 a 4096 caracteres.
     *
     * @param token token limpo
     * @return o token
     */
    private static String requireToken(String token) {
        if (token.length() < 20 || token.length() > 4096) {
            throw new InvalidInputException(INVALID_TOKEN);
        }
        return token;
    }

    /**
     * {@code cleanText} do Node: sem caracteres de controle nem espaços nas pontas.
     *
     * @param value valor
     * @return o texto
     */
    private static String clean(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return text.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]", "").strip();
    }
}
