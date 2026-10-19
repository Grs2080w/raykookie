package br.com.kntap.raykookie.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tira do histórico o que nunca pode ficar gravado: senhas, tokens, chaves e segredos (inclusive as do Mercado Pago).
 * Vale para o nome do campo, em qualquer nível, e para valores que têm cara de credencial.
 */
public final class AuditRedactor {

    /** Texto no lugar do valor escondido. */
    public static final String HIDDEN = "***";

    /** Nomes de campo que carregam segredo. */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(password|passwd|senha|secret|segredo|token|authorization|api[_-]?key|access[_-]?key|credential|"
                    + "card[_-]?number|cvv|security[_-]?code|private[_-]?key|pix[_-]?key|chave[_-]?pix).*");

    /** Valores que parecem credencial, mesmo num campo de nome inocente. */
    private static final Pattern SECRET_VALUE = Pattern.compile("(?i)^(bearer\\s+\\S+"
            + "|(APP_USR|TEST)-[0-9a-f-]{20,}"
            + "|sk_(live|test)_\\S+"
            + "|scrypt\\$\\S+"
            + "|eyJ[\\w-]{10,}\\.[\\w-]{10,}\\.[\\w-]*)$");

    /**
     * Classe só de funções estáticas.
     */
    private AuditRedactor() {
    }

    /**
     * Cópia do mapa com os segredos escondidos, em qualquer nível.
     *
     * @param values valores a gravar (pode ser {@code null})
     * @return a cópia segura, ou {@code null}
     */
    public static Map<String, Object> redact(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        values.forEach((key, value) -> safe.put(key, SECRET_KEY.matcher(key.toLowerCase(Locale.ROOT)).matches()
                ? HIDDEN : redactValue(value)));
        return safe;
    }

    /**
     * Esconde o valor se parecer credencial; desce em mapas e listas.
     *
     * @param value valor
     * @return o valor seguro
     */
    @SuppressWarnings("unchecked")
    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return redact((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AuditRedactor::redactValue).toList();
        }
        if (value instanceof CharSequence text && SECRET_VALUE.matcher(text).matches()) {
            return HIDDEN;
        }
        return value;
    }
}
