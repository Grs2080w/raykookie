package br.com.kntap.raykookie.catalog.application;

import br.com.kntap.raykookie.catalog.application.ports.PhotoStore;
import br.com.kntap.raykookie.catalog.application.ports.Recipes;
import br.com.kntap.raykookie.catalog.domain.Images;
import br.com.kntap.raykookie.kernel.DomainException;
import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.NotFoundException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.security.ShopOwner;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Fotos das receitas: envio (tipo conferido pelo conteúdo, JPEG sem metadados), exclusão e entrega pública.
 */
@Service
public class PhotoUseCases {

    /** Tamanho máximo da foto (o mesmo do Node; o filtro de tamanho já barra antes de ler). */
    static final int MAX_BYTES = 2_000_000;

    /** Fotos. */
    private final PhotoStore photos;

    /** Receitas. */
    private final Recipes recipes;

    /** A dona da loja, cujas fotos são públicas. */
    private final ShopOwner shopOwner;

    /**
     * Cria os casos de uso.
     *
     * @param photos    fotos
     * @param recipes   receitas
     * @param shopOwner dona da loja
     */
    public PhotoUseCases(PhotoStore photos, Recipes recipes, ShopOwner shopOwner) {
        this.photos = photos;
        this.recipes = recipes;
        this.shopOwner = shopOwner;
    }

    /**
     * Guarda a foto de uma receita.
     *
     * @param owner dona
     * @param id    receita
     * @param data  bytes enviados
     * @throws NotFoundException     se a receita não for da dona
     * @throws InvalidInputException sem imagem
     * @throws DomainException       413 grande demais, 415 tipo não aceito
     */
    public void upload(OwnerId owner, long id, byte[] data) {

        // ── 1. Receita da dona e arquivo presente e dentro do limite.
        if (!recipes.exists(owner, id)) {
            throw new NotFoundException(RecipeUseCases.NOT_FOUND);
        }
        if (data == null || data.length == 0) {
            throw new InvalidInputException("Nenhuma imagem enviada.");
        }
        if (data.length > MAX_BYTES) {
            throw new DomainException("A foto é grande demais (máximo de 2 MB).", 413);
        }

        // ── 2. Tipo pelo conteúdo; o JPEG perde EXIF (localização), XMP e comentários.
        String type = Images.sniff(data)
                .orElseThrow(() -> new DomainException("Use uma foto JPG, PNG ou WebP.", 415));
        photos.save(id, type, "image/jpeg".equals(type) ? Images.stripJpegMetadata(data) : data);
    }

    /**
     * Apaga a foto de uma receita.
     *
     * @param owner dona
     * @param id    receita
     */
    public void delete(OwnerId owner, long id) {
        photos.delete(id, owner.value());
    }

    /**
     * Foto para exibir: a dona vê as próprias; os outros, só as da loja.
     *
     * @param viewer    dona logada, se houver
     * @param rawId     id da receita como veio na URL
     * @param versioned se a URL traz {@code ?v=} (muda a cada foto nova)
     * @return a foto com o cache certo, ou vazio
     */
    public Optional<ServedPhoto> serve(Optional<OwnerId> viewer, String rawId, boolean versioned) {

        // ── 1. Id só de dígitos.
        if (!rawId.matches("\\d{1,18}")) {
            return Optional.empty();
        }
        long id = Long.parseLong(rawId);

        // ── 2. Foto de quem está vendo, ou da loja.
        Optional<PhotoStore.Photo> photo = viewer.flatMap(owner -> photos.load(id, owner.value()));
        if (photo.isEmpty() && !shopOwner.isPending()) {
            photo = photos.load(id, shopOwner.id().value());
        }

        // ── 3. Cache: a dona vê a prévia fresca; a URL versionada pode ficar guardada para sempre.
        String cache = viewer.isPresent()
                ? "private, max-age=60"
                : versioned ? "public, max-age=31536000, immutable" : "public, max-age=300";
        return photo.map(found -> new ServedPhoto(found.contentType(), found.data(), cache));
    }

    /**
     * Foto a entregar.
     *
     * @param contentType tipo
     * @param data        bytes
     * @param cache       valor do {@code Cache-Control}
     */
    public record ServedPhoto(String contentType, byte[] data, String cache) {
    }
}
