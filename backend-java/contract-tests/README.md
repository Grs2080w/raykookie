# Suíte de contrato (Tarefa 3.0)

Testes caixa-preta por HTTP (T02, G26): falam com um servidor **de pé** e conferem status,
`content-type`, corpo (JSON Schema em `src/test/resources/schemas/`) e mensagens de erro em português.
A mesma suíte roda contra o Node (grava o comportamento atual) e contra o Java (prova a paridade).

## Variáveis

| Variável | O quê |
|---|---|
| `CONTRACT_BASE_URL` | endereço do servidor sob teste (liga o perfil `contract`; sem ela os testes não rodam) |
| `CONTRACT_DB_URL` | URL JDBC do banco desse servidor, com usuário e senha. **Precisa terminar em `_test`**: a suíte cria dados nele |
| `CONTRACT_TARGET` | `node` ou `java`. Testes `@ChangedFromNode` só rodam com `java` |
| `CONTRACT_MP_FAKE_PORT` | porta do Mercado Pago falso; o servidor precisa de `MP_API_BASE=http://127.0.0.1:<porta>` |
| `CONTRACT_SHOP_OWNER` | o `SHOP_OWNER` do servidor (padrão `rayka`) |

## Contra o Node

O script `infra/contract-tests-node.sh` (na raiz do repositório) faz tudo: sobe um `postgres:17.11`
descartável, aplica as migrations do Node, sobe o `backend/` buildado apontado para ele e para o
Mercado Pago falso, roda a suíte e derruba tudo. Pré-requisitos: Docker, JDK 25 e `npm ci && npm run
build` em `backend/`.

```
infra/contract-tests-node.sh
```

## Contra o Java

Igual, com o servidor Java (`./mvnw -pl app spring-boot:run`) no lugar do Node e `CONTRACT_TARGET=java`.

## Regras

- Cada tarefa de módulo (Fases 4–6) **começa** escrevendo os testes das suas rotas aqui, roda contra o
  Node até passarem, e só então implementa o módulo Java até a mesma suíte passar contra o Java.
- Os dados de cada teste são criados pelo `ContractSeed` (contas com a senha `senha-de-teste-123` e sessão
  aberta). Os métodos de domínio (`recipeOnMenu`, `shopOrder`, `payLink`) entram com as tarefas que os usam.
- O Mercado Pago falso serve as gravações reais do sandbox (`src/test/resources/mp-recordings/`, Tarefa
  3.1). Enquanto elas não existem, a suíte cobre só rotas sem Mercado Pago.
