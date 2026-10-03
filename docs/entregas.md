# Entregas: o que dá para integrar (pesquisa de 03/10/2026)

Contexto: pedidos pequenos (doces), produzidos sob encomenda, em Presidente Prudente (SP). Hoje o app tem **retirada** (com endereço escolhido pela confeiteira) e **entrega** (o cliente informa o endereço), mas **sem taxa de entrega** no valor do pedido: o texto antigo "a taxa é avisada pelo WhatsApp" contradiz o acompanhamento só pelo app.

> Esta pesquisa vem de páginas públicas das empresas. **Cobertura em Presidente Prudente, preço por corrida e exigência de CNPJ precisam ser confirmados com cada uma** antes de qualquer desenvolvimento.

## Opções com API (integráveis)

| Opção | API | O que a pesquisa mostrou | A confirmar |
|---|---|---|---|
| **Lalamove** | Sim: criação de pedido, cotação na hora, agendamento de até 30 dias, cancelamento e status por webhook | Foco em B2B/PMEs; tem [página de API](https://www.lalamove.com/pt-br/empresas/solucoes-de-api) e integrações prontas com plataformas de loja | Se atende Presidente Prudente; preço mínimo por corrida; documentação em português |
| **Uber Direct** | Sim: cotação, tempo estimado, status | [Presente nos 27 estados](https://www.uber.com/br/pt-br/blog/uber-direct/), com mais de 180 municípios; contratação online para PMEs | Se Presidente Prudente está entre os municípios; mínimo de volume |
| **Borzo** (ex-Click Entregas) | Sim: REST/JSON (GET/POST), rastreio do entregador e webhooks de status | [Documentação pública](https://borzodelivery.com/in/business-api/doc); contato da API no Brasil: api.br@borzodelivery.com | Cobertura na cidade; moto ou carro; tempo de coleta |
| **Loggi** | Sim, para e-commerce e ERPs | Boa para envio entre cidades; o "Loggi Expresso" (motoboy na cidade) existe só onde há operação | Se há Loggi Expresso na cidade (provável que não) |

## Sem API pública (uso manual ou regra no app)

| Opção | Observação |
|---|---|
| **99 Entrega** | [Plataforma para empresas](https://99app.com/empresas/99-entrega/) com preço fixo por km, mas exige CNPJ e, na pesquisa, só aparece em São Paulo capital. Uso manual |
| **Mottu** | É aluguel de motos para entregadores, **não** um serviço de entrega. Descartada como integração |
| **Parceiro fixo (motoboy autônomo)** | O mais comum em cidade do interior. Sem API: vira uma regra de taxa no app (abaixo) e a confeiteira aciona a pessoa |
| **Rota única em horários fixos** | Ex.: entregas só às quartas e sábados à tarde, por bairro. Combina bem com `time_window` (manhã/tarde/noite) que o pedido já tem |
| **Retirada agendada** | Já existe. Mais barata e sem risco para o doce |

## Recomendação

1. **Primeiro, sem terceiros**: taxa de entrega por **bairro** ou por faixa de CEP, definida pela confeiteira, que entra no total e no Pix. Resolve a contradição do texto de hoje e serve para qualquer parceiro, fixo ou de aplicativo.
2. **Depois**, se o volume justificar, integrar **uma** opção com API (Lalamove ou Borzo; Uber Direct se atender a cidade), usando a cotação da corrida para mostrar a taxa ao cliente e o webhook para o status "saiu para entrega".
3. Cuidados com doces: a corrida precisa de caixa rígida e entregador que aceite carga frágil; avaliar entrega só em janelas fixas.

## Base interna proposta (aguarda sua aprovação)

- `delivery_zones` (por usuário): `{ bairro ou faixa de CEP, taxa, prazo/janela }`, configurada em "Endereço de retirada" → "Entrega".
- `shop_orders.delivery_fee` somado ao total (e ao valor do Pix), mostrado ao cliente antes de pagar.
- Mensagem de CEP fora das zonas: "ainda não entregamos aí, escolha retirada".
- Capacidade por janela (manhã/tarde/noite) para limitar quantas entregas cabem.
