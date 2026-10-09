package br.com.kntap.raykookie.purchasing.application.ports;

/**
 * Acesso às páginas dos sites dos fornecedores. A implementação só busca endereços públicos (SafeUrl em cada
 * redirecionamento), com tempo limite.
 */
public interface SupplierSiteClient {

    /**
     * Baixa uma página.
     *
     * @param url endereço
     * @return o HTML
     * @throws SiteException se o endereço não for seguro, o site não responder ou responder com erro
     */
    String fetch(String url);

    /**
     * Falha ao buscar a página, com a mensagem que fica em {@code last_error}.
     */
    class SiteException extends RuntimeException {

        /** Versão da serialização. */
        private static final long serialVersionUID = 1L;

        /**
         * Cria o erro.
         *
         * @param message mensagem
         */
        public SiteException(String message) {
            super(message);
        }
    }
}
