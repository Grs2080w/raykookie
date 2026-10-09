# IP do cliente atrás de proxy (Tarefa 2.4, G05)

## O que o Java faz

`ClientIpResolver` (módulo `platform/web`) só lê o `X-Forwarded-For` quando a conexão chega de um
proxy confiável (`TRUSTED_PROXIES`, padrão `127.0.0.1/32,::1/128`, ou seja, o `rproxy` local). O
cabeçalho é percorrido da direita para a esquerda, pulando os proxies confiáveis; o primeiro IP que
não é de proxy confiável é o cliente. O que o cliente escreve no cabeçalho fica à esquerda e nunca é
usado. Uma entrada que não é IP literal faz o resolvedor parar no último salto confiável (nunca há
consulta DNS).

No Node, a chave do limite de login era o `x-forwarded-for` cru: quem mandasse um valor diferente a
cada tentativa nunca era barrado.

## Passo 0: medição dos cabeçalhos reais — PENDENTE

O plano pede para medir, antes do corte, o que chega de cada origem:

| Origem | `remoteAddr` | `X-Forwarded-For` | `X-Real-IP` | `X-Vercel-Forwarded-For` |
|---|---|---|---|---|
| App Android direto na API | ? | ? | ? | ? |
| Navegador pelo site (rewrite da Vercel, `frontend/vercel.json`) | ? | ? | ? | ? |
| Servidor da Vercel (`BACKEND_URL`) | ? | ? | ? | ? |

A medição precisa de acesso à máquina de produção (ou de um endpoint temporário de diagnóstico no
Node, aprovado pelo dono), por isso não foi feita na sessão que implementou a Tarefa 2.4.

## Decisão pendente da medição

Se o tráfego do site chegar com um IP da Vercel como último salto não confiável, todos os usuários do
site dividiriam o mesmo "IP". Nesse caso (decisão já registrada no plano):

1. limites por IP passam a usar o IP informado pela Vercel **somente** quando a requisição trouxer o
   cabeçalho `x-rayka-edge` com o segredo `EDGE_SHARED_SECRET`, acrescentado por um middleware do
   `frontend/` no corte (Tarefa 7.3);
2. sem esse cabeçalho, o tráfego da Vercel cai em limites por conta/e-mail, nunca num limite global
   por IP.

Até a medição, os limites por IP (`CUSTOMER_SIGNUP`, `CARD_FAIL_IP`) devem ser usados junto com um
limite por conta/e-mail sempre que existir um.
