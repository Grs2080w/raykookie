# Raykookie

Confeitaria artesanal com loja online para os clientes e um painel completo para a confeiteira cuidar do negócio:
estoque, receitas com preço automático, pedidos, metas e custos.

## O que é

O Raykookie reúne, em um só lugar, tudo o que uma confeitaria precisa para vender e se organizar:

- **Para o cliente**: um site e um app Android onde dá para ver o cardápio, montar o pedido, aplicar cupom, pagar por
  Pix ou cartão e acompanhar cada pedido.
- **Para a confeiteira**: um painel (no site e em um app Android de gestão) para controlar ingredientes, receitas,
  preços, pedidos, cupons, metas e custos.

## Para o cliente

- **Cardápio** sempre atualizado, com foto, preço, selos de alergia e, quando a confeiteira escolhe mostrar, a lista de
  ingredientes.
- **Combos** com desconto: vários produtos num pedido só, com preço já calculado.
- **Cupons de desconto** aplicados no último passo do pedido, com o total cobrado já atualizado.
- **Pagamento** por Pix ou cartão, pelo Mercado Pago.
- **Conta** com login pelo Google ou por e-mail e senha, e o histórico de "meus pedidos".
- **App Android** do cliente, com o mesmo cardápio e os mesmos pedidos do site, e aviso quando sai uma versão nova.

## Para a confeiteira

- **Estoque** de ingredientes e embalagens, com custo de cada item.
- **Receitas** com preço calculado automaticamente a partir do custo dos ingredientes. Cada produto pode ter várias
  receitas; a padrão define o preço do cardápio, o lucro e as metas.
- **Cardápio e combos** controlados por ela: o que está ligado aparece para o cliente, na loja e no app.
- **Pedidos** chegando direto no painel, com acompanhamento de status e avisos no celular.
- **Cupons** com desconto de 1% a 90% e valor mínimo opcional.
- **Metas e custos** para saber quanto o negócio precisa vender e quanto sobra de lucro.
- **App Android de gestão**, para tocar a confeitaria do celular.

## Apps

| App | Para quem | O que faz |
| --- | --- | --- |
| **raykookie** | Clientes | Cardápio, pedidos e pagamento |
| **raykookie gestão** | Confeiteira | Estoque, receitas, pedidos, metas e custos |

Os dois apps são Android e podem ser baixados no próprio site (seção "Baixe o app" na página inicial). O app de gestão
só baixa com a conta da confeiteira logada.

## Tecnologia

- **Site e painel**: Next.js e React
- **API**: Java 25 com Spring Boot
- **Apps**: Flutter
- **Pagamentos**: Mercado Pago

## Mais informações

- [Arquitetura](docs/ARQUITETURA.md)
- [API](docs/API.md)

## Licença

Código **proprietário**, não é open source: o repositório é público só para rodar o CI. Todos os direitos reservados,
incluindo o nome **Raykookie**, a logo e a identidade visual. Copiar, usar, modificar ou redistribuir exige autorização
por escrito; ver [LICENSE](LICENSE).
