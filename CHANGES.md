**Alterações Prioritárias:**

1. **Dashboard de Vendas**: Adicionar coluna de "Lucro Total" nas metas de vendas, posicionada imediatamente abaixo da coluna de faturamento.
2. **Gestão de Pedidos**: Criar um campo específico e dedicado para endereço nos pedidos que aparecem no app/site de gestão de pedidos. O endereço deve ser exibido de forma clara e acessível.
3. **Webhook de Pagamento Automático**: Implementar um webhook no site da Raykookie que reconheça e processe automaticamente pedidos quando o pagamento é efetuado. Este webhook deve integrar-se ao sistema de gestão de pedidos existente. O banco a ser usado vai ser o mercado pago.
4. **Remoção de Método de Pagamento**: Remover a opção de pagamento por cartão das opções disponíveis no checkout.
5. **Otimização da Navegação Mobile**:
  - Na tela inicial do app mobile e na versão mobile do site, modificar a navegação para exibir apenas um botão ativo por vez (ou deixar um botão principal visível)
  - Quando o botão principal desaparecer da tela durante o scroll, a navbar fixa deve reaparece com uma animação suave e elegante
  - Garantir que sempre haja pelo menos um botão de navegação visível para o usuário, reduzindo a poluição visual da interface
  - Aplicar essa mudança consistentemente em ambas as plataformas (app mobile e site responsivo)
6. **Alterações na Visualização de Pedidos do Cliente**: Modificar como os pedidos do cliente são exibidos (adaptar conforme necessário para melhorar a experiência do usuário).

**Contexto:**

- Foco em experiência do usuário: reduzir elementos visuais na interface mobile, mantendo funcionalidade completa

Implemente cada alteração seguindo os padrões de código existentes no projeto. Priorize a experiência mobile nos pontos relacionados à navegação.