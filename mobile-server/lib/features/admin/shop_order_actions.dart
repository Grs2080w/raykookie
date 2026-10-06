import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/refund.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// Cancelar um pedido da loja (devolve o Pix se já estava pago) ou devolver um valor sem cancelar.
class ShopOrderActionSheet extends StatefulWidget {
  const ShopOrderActionSheet({super.key, required this.order, required this.cancel});
  final OrderModel order;
  final bool cancel; // true = cancelar pedido; false = devolver valor
  @override
  State<ShopOrderActionSheet> createState() => _ShopOrderActionSheetState();
}

class _ShopOrderActionSheetState extends State<ShopOrderActionSheet> {
  String? _code;
  final _note = TextEditingController(), _amount = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _note.dispose();
    _amount.dispose();
    super.dispose();
  }

  double get _left => (widget.order.shopTotal - widget.order.shopRefunded).clamp(0, double.infinity);

  Future<void> _go() async {
    final o = widget.order;
    final api = context.read<Session>().ownerApi;
    setState(() => _busy = true);
    try {
      final r = widget.cancel
          ? await api.post('/api/orders/shop/${o.shopOrderId}/cancelar', {'code': _code, 'note': _note.text.trim()})
          : await api.post('/api/orders/shop/${o.shopOrderId}/devolver', {'amount': _amount.text.trim(), 'note': _note.text.trim()});
      final back = r is Map ? (r['refunded'] as num?)?.toDouble() ?? 0 : 0;
      Toast.ok(back > 0 ? 'Feito. ${money(back)} devolvidos ao cliente.' : 'Pedido cancelado');
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final o = widget.order;
    return Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
      if (widget.cancel) ...[
        Text(
          o.shopPaid
              ? 'Já foi pago. Ao cancelar, o valor de ${money(_left)} volta para a cliente automaticamente, e ela recebe uma notificação com o motivo.'
              : 'Ainda não foi pago. A cliente vê o cancelamento e o motivo, e recebe uma notificação.',
          style: TextStyle(color: c.muted, height: 1.4),
        ),
        const SizedBox(height: 12),
        const Text('Motivo (a cliente vê)', style: TextStyle(fontWeight: FontWeight.w700)),
        RadioGroup<String>(
          groupValue: _code,
          onChanged: (v) => setState(() => _code = v),
          child: Column(children: [for (final r in storeCancelReasons) RadioListTile<String>(dense: true, contentPadding: EdgeInsets.zero, value: r.id, title: Text(r.label))]),
        ),
        AppTextField(
          label: _code == 'outro' ? 'Conte o motivo (a cliente vê)' : 'Mensagem para a cliente (opcional)',
          controller: _note,
          maxLength: 300,
          maxLines: 2,
          onChanged: (_) => setState(() {}), // "Outro motivo" só libera o botão com a explicação
        ),
      ] else ...[
        Text('Devolve parte do valor por Pix sem cancelar o pedido (por exemplo, se faltou um item). Ainda dá para devolver até ${money(_left)}.', style: TextStyle(color: c.muted, height: 1.4)),
        const SizedBox(height: 12),
        AppTextField(label: 'Valor (R\$)', controller: _amount, keyboardType: const TextInputType.numberWithOptions(decimal: true), formatters: [NumberFormatter()]),
        AppTextField(label: 'Motivo (o cliente vê)', controller: _note, maxLength: 300),
      ],
      const SizedBox(height: 8),
      BusyButton(
        label: widget.cancel ? (o.shopPaid && _left > 0 ? 'Cancelar e devolver ${money(_left)}' : 'Cancelar pedido') : 'Devolver',
        busy: _busy,
        onPressed: widget.cancel
            ? (_code == null || (_code == 'outro' && _note.text.trim().length < 3) ? null : _go)
            // confere na hora do toque: o campo não redesenha a folha a cada tecla
            : () => _amount.text.trim().isEmpty ? Toast.error('Informe o valor da devolução.') : _go(),
      ),
    ]);
  }
}
