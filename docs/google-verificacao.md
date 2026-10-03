# Verificação do login com Google (Raykookie)

A verificação anterior foi recusada por 4 motivos. O que o código já resolve e o que falta **você** fazer.

| Motivo apontado pelo Google | No código | Você |
|---|---|---|
| Domínio da página inicial não está registrado para você | Opcional: `GOOGLE_SITE_VERIFICATION=<token>` no `.env` emite a meta `google-site-verification` em todas as páginas | No [Search Console](https://search.google.com/search-console), adicione a propriedade de **domínio** `kntap.com.br` (registro TXT no DNS) **com a mesma conta Google do projeto OAuth**. Depois, **espere 24h** antes de reenviar |
| Página inicial protegida por login | `/`, `/termos`, `/privacidade` e `/excluir-conta` são públicas (sem login, sem redirecionamento) | Nada |
| Página inicial não explica a finalidade do app | Seção **"Sobre o app Raykookie"** na landing (`/#sobre-o-app`): o que o app faz e para que serve o login com Google | Nada |
| Nome do app no consentimento ≠ nome na página | Nome padronizado como **Raykookie** (título, og:site_name, applicationName, cabeçalhos, Termos, Privacidade, nome dos apps) | No console do OAuth, use exatamente **Raykookie** |

## Console do Google (APIs e serviços → Tela de consentimento OAuth)
- Nome do app: `Raykookie`
- Logotipo: o mesmo da página inicial
- Página inicial: `https://raykookie.kntap.com.br/`
- Política de privacidade: `https://raykookie.kntap.com.br/privacidade`
- Termos de serviço: `https://raykookie.kntap.com.br/termos`
- Domínios autorizados: `kntap.com.br`
- Escopos: somente `openid`, `email` e `profile` (não sensíveis)
- Reenviar para verificação só **depois** de 24h da confirmação no Search Console

## Conferência rápida
```
curl -sI https://raykookie.kntap.com.br/ | head -1          # HTTP 200, sem redirecionar
curl -s https://raykookie.kntap.com.br/ | grep -o "Sobre o app Raykookie"
curl -s https://raykookie.kntap.com.br/ | grep -o "<title>[^<]*"   # Raykookie · Confeitaria feita à mão
```
