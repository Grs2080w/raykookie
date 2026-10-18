# raykookie gestão (app da confeiteira)

App de **gestão** da raykookie: início (resumo do mês), **estoque**, **receitas** (preço automático e foto para a loja), **pedidos**, **metas**, **categorias** e **custos e ajustes**. Só abre com a conta da confeiteira. Pacote Android `br.com.kntap.raykookie_gestao`, ícone escuro (o mesmo cookie do app do cliente sobre `#1E1A1D`). Código compartilhado em [`../mobile-common`](../mobile-common).

## Biometria

Depois do primeiro acesso ao app (incluindo o primeiro depois de uma atualização), o app pergunta se a confeiteira quer **entrar com biometria** (digital ou rosto). A escolha fica ligada à conta, no cofre do aparelho. Ativada, o app abre trancado e pede a biometria (também ao voltar depois de 30 s em segundo plano); dá para usar o PIN do aparelho como reserva e **Sair da conta** na própria tela de bloqueio. Liga e desliga em **Mais → Entrar com biometria**. É uma trava de tela do app: a sessão continua guardada no aparelho.

## Ler nota fiscal (QR code)

**Estoque → Ler nota** abre a câmera para o QR code do cupom fiscal (NFC-e). O celular baixa a página da nota no portal da SEFAZ e o backend interpreta os itens. Na tela de conferência:

- itens que **já estão no estoque** são somados (quantidade em embalagens, convertendo kg/L e o tamanho escrito no nome), com opção de atualizar o preço da embalagem;
- itens **sem correspondência** (ou só parecidos) o app **pergunta**: aceitar (cria o ingrediente, dá para ajustar nome/unidade/embalagem/preço) ou recusar;
- a mesma nota não entra duas vezes. Sem câmera/permissão, dá para **colar o link** da nota.

Limite: o leitor entende o layout padrão do "Consulta NFC-e" (SP, PR, MS e vários outros) e um formato alternativo em texto; portais com captcha ou layout diferente dão aviso e a nota pode ser lançada à mão.

Versão: `version: X.Y.Z+N` no `pubspec.yaml` (aparece no login e na aba **Mais**; o app avisa quando há build mais novo). **Não edite o build à mão:** `bash infra/publish-apps.sh server` sobe sozinho e publica o APK. O download no site fica em **Custos e ajustes** do painel (só logada).

## Rodar

```bash
flutter pub get
flutter run --dart-define=API_BASE=https://api.raykookie.com.br
flutter run --dart-define=API_BASE=http://10.0.2.2:10005     # PC local, no emulador Android
```

## Testes e build

```bash
flutter analyze && flutter test
RAYKA_API=http://127.0.0.1:10005 RAYKA_USER=usuario RAYKA_PASS=... flutter test test/integration
bash ../infra/publish-apps.sh server
```
