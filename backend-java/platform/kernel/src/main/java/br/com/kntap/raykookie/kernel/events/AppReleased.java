package br.com.kntap.raykookie.kernel.events;

/**
 * Versão nova de um app publicada em {@code downloads/} (o CD grava o APK e o manifesto): avisa todos os aparelhos
 * daquele app para atualizarem pelo próprio app.
 *
 * @param app     {@code client} (aparelhos dos clientes) ou {@code server} (aparelhos da dona)
 * @param version nome da versão (ex.: {@code 1.0.0})
 * @param build   número do build
 */
public record AppReleased(String app, String version, int build) {
}
