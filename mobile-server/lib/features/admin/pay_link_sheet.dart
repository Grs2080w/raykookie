import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/share_menu.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// Link de pagamento Pix de um pedido registrado à mão: a confeiteira confere o valor (pode ser menos que o total, se já houve sinal) e compartilha.
class PayLinkSheet extends StatefulWidget {
  const PayLinkSheet({super.key, required this.order});
  final OrderModel order;
  @override
  State<PayLinkSheet> createState() => _PayLinkSheetState();
}

class _PayLinkSheetState extends State<PayLinkSheet> {
  late final _amount = TextEditingController(text: _fmt(_max));
  bool _busy = false;

  /// Total do pedido arredondado a centavos, como o servidor cobra (qty é fracionada: 0,5 x 45,99 = 22,995 vira 23,00).
  double get _max => (widget.order.total * 100).round() / 100;

  static String _fmt(double v) => v.toStringAsFixed(2).replaceAll('.', ',');

  @override
  void dispose() {
    _amount.dispose();
    super.dispose();
  }

  /// Valor digitado (arredondado a 2 casas), ou null se vazio, zero ou acima do total do pedido.
  double? get _value {
    final v = parseLocaleNumber(_amount.text);
    if (v == null || v.isNaN) {
      return null;
    }
    final r = (v * 100).round() / 100;
    if (r < 0.01 || r > _max) {
      return null;
    }
    return r;
  }

  /// Pede o link ao servidor com o valor digitado; null se o valor é inválido ou a API recusou (o aviso já foi mostrado).
  Future<({String url, double amount})?> _link() async {
    final v = _value;
    if (v == null) {
      Toast.error(
        'Informe um valor entre R\$ 0,01 e ${money(_max)}.',
      );
      return null;
    }
    setState(() => _busy = true);
    try {
      final r = await context.read<Session>().ownerApi.post(
        '/api/orders/${widget.order.id}/pagamento',
        {'amount': _fmt(v)},
      );
      return (url: (r as Map)['url'] as String, amount: v);
    } on ApiException catch (e) {
      Toast.error(e.message);
      return null;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _share() async {
    final l = await _link();
    if (l == null || !mounted) return;
    final o = widget.order;
    await shareText(
      'Olá! Segue o link para pagar o pedido #${o.number} (${money(l.amount)}): ${l.url}',
      subject: 'Pagamento do pedido #${o.number}',
      copiedMsg: 'Link copiado',
    );
    if (mounted) Navigator.of(context).pop(true);
  }

  Future<void> _copy() async {
    final l = await _link();
    if (l == null) return;
    await Clipboard.setData(ClipboardData(text: l.url));
    Toast.ok('Link copiado');
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final o = widget.order;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Manda para ${o.customer} um link com o Pix deste pedido. Quando ele pagar, o pedido vira "pago" sozinho.',
          style: TextStyle(color: c.muted, height: 1.4),
        ),
        const SizedBox(height: 12),
        AppTextField(
          label: 'Valor a cobrar (R\$)',
          controller: _amount,
          helper:
              'Total do pedido: ${money(o.total)}. Se já recebeu um sinal, cobre só o restante.',
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          formatters: [NumberFormatter()],
        ),
        const SizedBox(height: 8),
        BusyButton(label: 'Compartilhar link', busy: _busy, onPressed: _share),
        const SizedBox(height: 8),
        SizedBox(
          width: double.infinity,
          child: OutlinedButton(
            onPressed: _busy ? null : _copy,
            style: OutlinedButton.styleFrom(minimumSize: const Size(0, 46)),
            child: const Text('Copiar link'),
          ),
        ),
      ],
    );
  }
}
