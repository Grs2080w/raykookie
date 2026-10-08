package br.com.kntap.raykookie;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada do backend da Raykookie. Liga os módulos e sobe o servidor HTTP.
 */
@SpringBootApplication
public class RaykookieApplication {

    /**
     * Inicia a aplicação Spring Boot.
     *
     * @param args argumentos de linha de comando repassados ao Spring
     */
    public static void main(String[] args) {
        SpringApplication.run(RaykookieApplication.class, args);
    }
}
