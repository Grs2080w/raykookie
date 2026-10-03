# raykookie (app do cliente)

App para os clientes da raykookie: **cardápio** (vem das receitas do painel), **login** (Google ou e-mail e senha), **meus pedidos** e **novo pedido**. Não tem nenhuma tela de gestão. Pacote Android `br.com.kntap.raykookie`. Código compartilhado em [`../mobile-common`](../mobile-common).

Versão: `version: X.Y.Z+N` no `pubspec.yaml` (aparece no rodapé da tela inicial; o app avisa quando há build mais novo). **Não edite o build à mão:** `bash infra/publish-apps.sh client` sobe sozinho e publica o APK para o site.

## Rodar

```bash
flutter pub get
flutter run --dart-define=API_BASE=https://raykookie.kntap.com.br           # produção
flutter run --dart-define=API_BASE=http://10.0.2.2:10005                    # PC local, no emulador Android
```

`API_BASE` é o endereço do **site** (a API responde em `/api`). Sem ele usa o de produção.

## Login com Google

1. No Google Cloud crie o ID OAuth **"Aplicativo da Web"** (o mesmo `GOOGLE_CLIENT_ID` do backend) e os IDs **Android** (pacote `br.com.kntap.raykookie` + SHA-1 da chave de release, `keytool -list -v -keystore ~/.raykookie/release.jks`) e **iOS**.
2. Compile com `--dart-define=GOOGLE_SERVER_CLIENT_ID=<ID do cliente web>` (o `infra/publish-apps.sh` já faz isso, lendo o `GOOGLE_CLIENT_ID` de `backend/.env`).
3. iOS: `GIDClientID` e esquema de URL no `Info.plist`.

## Testes e build

```bash
flutter analyze && flutter test
RAYKA_API=http://127.0.0.1:10005 RAYKA_USER=usuario RAYKA_PASS=... flutter test test/integration   # contra um backend no ar
bash ../infra/publish-apps.sh client                                                                # APK de release em downloads/
```
Android exige JDK 17 ou 21 (não 26): `flutter config --jdk-dir=/usr/lib/jvm/java-21-openjdk-amd64`.
