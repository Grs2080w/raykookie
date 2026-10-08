package br.com.kntap.raykookie.web;

/**
 * Corpo de toda resposta de erro da API: {@code {"error": "<mensagem em português>"}}, o mesmo formato
 * do {@code fail()} do Node ({@code backend/lib/api.ts}).
 *
 * @param error mensagem mostrada ao cliente
 */
public record ErrorBody(String error) {

    /**
     * Serializa o corpo sem depender do conversor JSON do Spring (usado pelos filtros, que respondem
     * antes de a requisição chegar ao Spring MVC).
     *
     * @return o JSON {@code {"error":"..."}}
     */
    public String toJson() {
        return "{\"error\":\"" + escape(error) + "\"}";
    }

    /**
     * Escapa um texto para dentro de uma string JSON.
     *
     * @param text texto livre
     * @return o texto com aspas, barras e caracteres de controle escapados
     */
    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        text.chars().forEach(c -> out.append(escapeChar((char) c)));
        return out.toString();
    }

    /**
     * Escapa um caractere para JSON.
     *
     * @param c o caractere
     * @return a forma escapada, ou o próprio caractere
     */
    private static String escapeChar(char c) {
        return switch (c) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c);
        };
    }
}
