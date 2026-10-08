package br.com.kntap.raykookie.identity.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;

/**
 * Senhas no formato do Node ({@code backend/lib/auth.ts}): {@code scrypt$<sal hex>$<hash hex>}, com
 * N=16384, r=8, p=1, 64 bytes e a senha em UTF-8. As senhas criadas pelo Node continuam valendo no Java.
 * Usado pela dona ({@code identity}) e pelos clientes ({@code customers}).
 */
public final class ScryptPasswordHasher {

    /** Hash fictício para conferir senha mesmo sem conta: o tempo de resposta não revela se a conta existe. */
    public static final String DUMMY_HASH = "scrypt$00$00";

    /** Parâmetro de custo N do scrypt. */
    private static final int COST = 16384;

    /** Parâmetro de bloco r. */
    private static final int BLOCK_SIZE = 8;

    /** Paralelismo p. */
    private static final int PARALLELISM = 1;

    /** Tamanho do hash gerado. */
    private static final int KEY_LENGTH = 64;

    /** Tamanho do sal. */
    private static final int SALT_LENGTH = 16;

    /** Gerador do sal. */
    private final SecureRandom random = new SecureRandom();

    /**
     * Gera o hash de uma senha nova, com sal aleatório.
     *
     * @param password senha
     * @return {@code scrypt$<32 hex>$<128 hex>}
     */
    public String hash(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        byte[] hash = scrypt(password, salt, KEY_LENGTH);
        return "scrypt$" + HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(hash);
    }

    /**
     * Confere uma senha contra o hash guardado, em tempo constante na comparação.
     *
     * @param password senha digitada
     * @param stored   hash guardado
     * @return verdadeiro se a senha confere
     */
    public boolean verify(String password, String stored) {

        // ── 1. Separa esquema, sal e hash; formato estranho nunca confere.
        String[] parts = stored == null ? new String[0] : stored.split("\\$", -1);
        if (parts.length != 3 || !"scrypt".equals(parts[0]) || parts[1].isEmpty() || parts[2].isEmpty()) {
            return false;
        }
        byte[] salt = hex(parts[1]);
        byte[] expected = hex(parts[2]);
        if (salt.length == 0 || expected.length == 0) {
            return false;
        }

        // ── 2. Recalcula com o mesmo sal e o mesmo tamanho, e compara sem atalho.
        byte[] actual = scrypt(password, salt, expected.length);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * scrypt com os parâmetros do Node.
     *
     * @param password  senha
     * @param salt      sal
     * @param keyLength tamanho do hash
     * @return o hash
     */
    private static byte[] scrypt(String password, byte[] salt, int keyLength) {
        return SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, COST, BLOCK_SIZE, PARALLELISM,
                keyLength);
    }

    /**
     * Lê hexadecimal; texto inválido vira vazio (a senha não confere).
     *
     * @param text hexadecimal
     * @return os bytes, ou vazio
     */
    private static byte[] hex(String text) {
        try {
            return HexFormat.of().parseHex(text);
        } catch (IllegalArgumentException invalid) {
            return new byte[0];
        }
    }
}
