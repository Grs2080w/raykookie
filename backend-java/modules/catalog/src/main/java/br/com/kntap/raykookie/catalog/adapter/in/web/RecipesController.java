package br.com.kntap.raykookie.catalog.adapter.in.web;

import br.com.kntap.raykookie.catalog.api.PricedRecipe;
import br.com.kntap.raykookie.catalog.application.PhotoUseCases;
import br.com.kntap.raykookie.catalog.application.RecipePricing;
import br.com.kntap.raykookie.catalog.application.RecipeUseCases;
import br.com.kntap.raykookie.catalog.application.RecipeVariantUseCases;
import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.security.AccessLevel;
import br.com.kntap.raykookie.security.CurrentActor;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receitas da dona, o editor de cardápio e as fotos: {@code /api/recipes}.
 */
@RestController
public class RecipesController {

    /** Casos de uso das receitas. */
    private final RecipeUseCases recipes;

    /** Preço das receitas. */
    private final RecipePricing pricing;

    /** Fotos. */
    private final PhotoUseCases photos;

    /** Quem está chamando. */
    private final CurrentActor actor;

    /** Versões de receita. */
    private final RecipeVariantUseCases variants;

    /**
     * Cria o controller.
     *
     * @param recipes  casos de uso das receitas
     * @param pricing  preço das receitas
     * @param photos   fotos
     * @param actor    quem está chamando
     * @param variants versões de receita
     */
    public RecipesController(RecipeUseCases recipes, RecipePricing pricing, PhotoUseCases photos,
            CurrentActor actor, RecipeVariantUseCases variants) {
        this.recipes = recipes;
        this.pricing = pricing;
        this.photos = photos;
        this.actor = actor;
        this.variants = variants;
    }

    /**
     * Receitas já precificadas (custo, preço sugerido, margem).
     *
     * @return as receitas
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/recipes")
    public List<Map<String, Object>> list() {
        return pricing.priced(actor.requireOwner()).stream().map(PricedRecipe::json).toList();
    }

    /**
     * Cria uma receita.
     *
     * @param body dados
     * @return a linha criada
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/recipes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return recipes.save(actor.requireOwner(), null, body);
    }

    /**
     * Uma receita com os itens.
     *
     * @param id receita
     * @return a receita
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/recipes/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return recipes.get(actor.requireOwner(), id);
    }

    /**
     * Edita uma receita e troca os itens.
     *
     * @param id   receita
     * @param body dados
     * @return a linha gravada
     */
    @Access(AccessLevel.OWNER)
    @PutMapping("/api/recipes/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return recipes.save(actor.requireOwner(), id, body);
    }

    /**
     * Apaga uma receita.
     *
     * @param id receita
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/recipes/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        recipes.delete(actor.requireOwner(), id);
        return Map.of("ok", true);
    }

    /**
     * Nova versão de receita do produto (aba "+").
     *
     * @param id   produto
     * @param body {@code name} e {@code copy_from} (opcionais)
     * @return as versões do produto
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/recipes/{id}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> createVariant(@PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body) {
        return variants.create(actor.requireOwner(), id, body == null ? Map.of() : body);
    }

    /**
     * Salva uma versão de receita.
     *
     * @param id      produto
     * @param variant versão
     * @param body    {@code name}, {@code yield_units}, {@code prep_hours} e {@code items}
     * @return as versões do produto
     */
    @Access(AccessLevel.OWNER)
    @PutMapping("/api/recipes/{id}/variants/{variant}")
    public List<Map<String, Object>> saveVariant(@PathVariable long id, @PathVariable long variant,
            @RequestBody Map<String, Object> body) {
        return variants.save(actor.requireOwner(), id, variant, body);
    }

    /**
     * Exclui uma versão de receita (não a padrão).
     *
     * @param id      produto
     * @param variant versão
     * @return as versões do produto
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/recipes/{id}/variants/{variant}")
    public List<Map<String, Object>> deleteVariant(@PathVariable long id, @PathVariable long variant) {
        return variants.delete(actor.requireOwner(), id, variant);
    }

    /**
     * Escolhe a versão padrão do produto.
     *
     * @param id      produto
     * @param variant versão
     * @return as versões do produto
     */
    @Access(AccessLevel.OWNER)
    @PostMapping("/api/recipes/{id}/variants/{variant}/default")
    public List<Map<String, Object>> makeDefault(@PathVariable long id, @PathVariable long variant) {
        return variants.makeDefault(actor.requireOwner(), id, variant);
    }

    /**
     * Lista enxuta para o editor de cardápio.
     *
     * @return as receitas
     */
    @Access(AccessLevel.OWNER)
    @GetMapping("/api/recipes/cardapio")
    public List<Map<String, Object>> menuEditor() {
        return recipes.menuEditor(pricing.priced(actor.requireOwner()));
    }

    /**
     * Liga/desliga no cardápio e troca os selos.
     *
     * @param id   receita
     * @param body {@code show_on_menu} e/ou {@code tags}
     * @return {@code id}, {@code show_on_menu} e {@code tags}
     */
    @Access(AccessLevel.OWNER)
    @PatchMapping("/api/recipes/{id}/cardapio")
    public Map<String, Object> menuFlags(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return recipes.updateMenuFlags(actor.requireOwner(), id, body);
    }

    /**
     * Envia ou troca a foto (o corpo é a imagem).
     *
     * @param id   receita
     * @param data bytes da imagem
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @PutMapping("/api/recipes/{id}/photo")
    public Map<String, Object> uploadPhoto(@PathVariable long id, @RequestBody(required = false) byte[] data) {
        photos.upload(actor.requireOwner(), id, data);
        return Map.of("ok", true);
    }

    /**
     * Apaga a foto.
     *
     * @param id receita
     * @return {@code {"ok":true}}
     */
    @Access(AccessLevel.OWNER)
    @DeleteMapping("/api/recipes/{id}/photo")
    public Map<String, Object> deletePhoto(@PathVariable long id) {
        photos.delete(actor.requireOwner(), id);
        return Map.of("ok", true);
    }
}
