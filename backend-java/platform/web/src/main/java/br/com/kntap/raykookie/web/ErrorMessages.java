package br.com.kntap.raykookie.web;

/**
 * Mensagens em português dos erros que não vêm de uma regra de negócio. As que já existem no Node
 * ({@code backend/lib/api.ts}) são copiadas sem mudar uma letra.
 */
public final class ErrorMessages {

    /** 400 genérico (corpo ilegível, campo inválido sem mensagem própria). */
    public static final String INVALID_INPUT = "Dados inválidos.";

    /** 401: mesma mensagem do {@code route()} do Node. */
    public static final String SESSION_EXPIRED = "Sessão expirada. Entre novamente.";

    /** 403. */
    public static final String FORBIDDEN = "Acesso negado.";

    /** 404 de rota ou recurso inexistente. */
    public static final String NOT_FOUND = "Não encontrado.";

    /** 405. */
    public static final String METHOD_NOT_ALLOWED = "Método não permitido.";

    /** 406. */
    public static final String NOT_ACCEPTABLE = "Formato de resposta não suportado.";

    /** 409 genérico. */
    public static final String CONFLICT = "Não foi possível concluir agora. Atualize e tente de novo.";

    /** 413 genérico. */
    public static final String TOO_LARGE = WebProperties.DEFAULT_MESSAGE;

    /** 413 de uma foto acima do limite por arquivo (mesma mensagem do Node). */
    public static final String PHOTO_TOO_LARGE = "Uma das fotos é grande demais (máximo de 2 MB).";

    /** 413 de um formulário com fotos acima do limite total (mesma mensagem do Node). */
    public static final String FORM_TOO_LARGE = "O envio é grande demais (máximo de 12 MB no total).";

    /** 415. */
    public static final String UNSUPPORTED_MEDIA_TYPE = "Tipo de conteúdo não suportado.";

    /** 429 genérico. */
    public static final String TOO_MANY_REQUESTS = "Muitas tentativas. Aguarde um pouco e tente de novo.";

    /** 500: mesma mensagem do Node. */
    public static final String SERVER_ERROR = "Algo deu errado no servidor. Tente de novo.";

    /** 503: banco fora do ar ou travado. */
    public static final String UNAVAILABLE = "Serviço indisponível. Tente de novo em instantes.";

    /** SQL 22003 (número fora do intervalo do tipo): mesma mensagem do Node. */
    public static final String NUMBER_TOO_LARGE = "Algum número está grande demais.";

    /** SQL 23505 (unicidade): mesma mensagem do Node. */
    public static final String DUPLICATE = "Já existe um registro com esse nome.";

    /** SQL 23503 (chave estrangeira): mesma mensagem do Node. */
    public static final String IN_USE = "Este item está em uso e não pode ser removido.";

    /** SQL 23514 (restrição CHECK): mesma mensagem do Node. */
    public static final String OUT_OF_RANGE = "Algum valor está fora do permitido.";

    /**
     * Sem instâncias: só constantes e a tabela por status.
     */
    private ErrorMessages() {
    }

    /**
     * Mensagem genérica de um status HTTP (usada quando o erro não traz mensagem própria).
     *
     * @param status status HTTP
     * @return a mensagem em português
     */
    public static String forStatus(int status) {
        return switch (status) {
            case 401 -> SESSION_EXPIRED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 413 -> TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> TOO_MANY_REQUESTS;
            case 502, 503, 504 -> UNAVAILABLE;
            default -> status >= 500 ? SERVER_ERROR : INVALID_INPUT;
        };
    }
}
