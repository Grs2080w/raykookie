/// Devolução (Pix de volta ou estorno no cartão, conforme o pagamento): motivos e regras. Porte de shared/refund.ts (o servidor valida de novo e decide quem pode devolver o quê).
const refundWindowHours = 48;
const maxRefundPhotos = 5;
const maxRefundPhotoBytes = 2000000;
const minRefundDescription = 20;

class Reason {
  const Reason(this.id, this.label, {this.photos = 'nao', this.priority = false, this.hint = ''});
  final String id, label;

  /// 'sim' = foto obrigatória quando o produto já foi recebido; 'opcional'; 'nao' = não há produto para fotografar.
  final String photos;
  final bool priority;
  final String hint;
}

/// Cancelamento pelo cliente, antes de a produção começar.
const cancelReasons = [
  Reason('errei_pedido', 'Fiz o pedido errado (sabor, item ou quantidade)'),
  Reason('data_horario', 'A data ou o horário não servem mais'),
  Reason('retirada_entrega', 'Não vou conseguir retirar ou receber'),
  Reason('pagamento', 'Tive problema com o pagamento ou o Pix'),
  Reason('demora_confirmacao', 'A confirmação está demorando'),
  Reason('preco', 'Achei caro ou encontrei outra opção'),
  Reason('nao_preciso', 'Não preciso mais (o evento foi cancelado ou desisti)'),
  Reason('outro', 'Outro motivo'),
];

/// Pedido de devolução, depois de a produção ter começado.
const refundReasons = [
  Reason('nao_recebi', 'Não recebi ou não ficou pronto no horário combinado'),
  Reason('item_errado', 'Veio item, sabor ou recheio errado', photos: 'sim'),
  Reason('faltou_item', 'Faltou item ou veio em menor quantidade', photos: 'sim'),
  Reason('avariado', 'Chegou amassado, quebrado ou derretido', photos: 'sim'),
  Reason('qualidade', 'Problema de qualidade (cru, queimado, seco, sabor estranho)', photos: 'sim'),
  Reason('estragado', 'Estragado, com mofo ou com algo estranho dentro', photos: 'sim', priority: true),
  Reason('alergia', 'Tinha ingrediente que não estava informado ou causou reação', photos: 'opcional', priority: true, hint: 'Se houver reação grave, procure atendimento médico.'),
  Reason('desisti', 'Desisti do pedido (já está em produção)', hint: 'Como o pedido já foi feito só para você, a devolução nesse caso é improvável.'),
  Reason('outro', 'Outro motivo', photos: 'sim'),
];

/// Cancelamento feito pela loja (o app de gestão escolhe; o cliente lê).
const storeCancelReasons = [
  Reason('falta_ingrediente', 'Faltou ingrediente'),
  Reason('sem_agenda', 'Não conseguimos produzir a tempo'),
  Reason('fora_area', 'Não entregamos nesse endereço'),
  Reason('problema_producao', 'Tivemos um problema na produção'),
  Reason('pedido_cliente', 'Cancelado a pedido do cliente'),
  Reason('outro', 'Outro motivo'),
];

/// Motivos para negar uma devolução.
const denyReasons = [
  Reason('fora_prazo', 'Fora do prazo'),
  Reason('sem_comprovacao', 'As fotos não mostram o problema'),
  Reason('produto_conforme', 'O produto estava conforme o pedido'),
  Reason('consumido', 'O produto foi consumido em maior parte'),
  Reason('desistencia_producao', 'Desistência com o pedido já produzido'),
  Reason('outro', 'Outro motivo'),
];

/// Encerramentos feitos pelo próprio sistema (ninguém escolhe; o cliente e a loja veem o motivo).
const fullRefundCode = 'devolucao_total';
const systemReasons = [
  Reason(fullRefundCode, 'Todo o valor foi devolvido'),
];

String reasonLabel(String id) {
  for (final r in [...cancelReasons, ...refundReasons, ...storeCancelReasons, ...denyReasons, ...systemReasons]) {
    if (r.id == id) return r.label;
  }
  return id;
}
