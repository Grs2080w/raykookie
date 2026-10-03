# raykookie_common

Pacote Dart usado por `mobile-client` e `mobile-server`: cliente HTTP da API (token Bearer), sessão (confeiteira e cliente), modelos (espelho de `shared/types.ts`), cálculo de preço, validação de pedido, leitura de números pt-BR, versão instalada/publicada, tema da marca e widgets.

Os cálculos têm os **mesmos casos de teste** do TypeScript (`backend/tests`): app e servidor precisam dar o mesmo resultado.

```bash
flutter pub get && flutter analyze && flutter test
```
