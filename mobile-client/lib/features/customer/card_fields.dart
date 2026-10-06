// Formulário de cartão: campos nativos do Flutter (nossa cara) com dicas de autofill — não existe SDK oficial
// do Mercado Pago para Flutter, então o número/validade/CVV são tokenizados por uma chamada HTTPS direta à
// API pública dele (só com a chave pública, nunca a secreta), sem passar pelo nosso backend. Nada abre
// (nem WebView, nem app externo); tudo acontece dentro desta tela.
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/shop_rules.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

class CardTokenResult {
  CardTokenResult({required this.cardToken, required this.paymentMethodId, required this.cpf, required this.installments});
  final String cardToken, paymentMethodId, cpf;
  final int installments;
}

String _formatCardNumber(String v) {
  final d = onlyDigits(v, 19);
  final b = StringBuffer();
  for (var i = 0; i < d.length; i++) {
    if (i > 0 && i % 4 == 0) b.write(' ');
    b.write(d[i]);
  }
  return b.toString();
}

String _formatExpiry(String v) {
  final d = onlyDigits(v, 4);
  return d.length > 2 ? '${d.substring(0, 2)}/${d.substring(2)}' : d;
}

class CardFields extends StatefulWidget {
  const CardFields({super.key, required this.publicKey, required this.amount});
  final String publicKey;
  final double amount;
  @override
  State<CardFields> createState() => CardFieldsState();
}

class CardFieldsState extends State<CardFields> {
  final _number = TextEditingController();
  final _expiry = TextEditingController();
  final _cvv = TextEditingController();
  final _name = TextEditingController();
  final _cpf = TextEditingController();
  List<int> _options = [];
  int _installments = 1;
  String _paymentMethodId = '';
  String _lastBin = '';

  @override
  void dispose() {
    _number.dispose();
    _expiry.dispose();
    _cvv.dispose();
    _name.dispose();
    _cpf.dispose();
    super.dispose();
  }

  Future<void> _onNumberChanged(String v) async {
    final digits = onlyDigits(v, 19);
    final bin = digits.length >= 6 ? digits.substring(0, 6) : '';
    if (bin == _lastBin) return;
    _lastBin = bin;
    if (bin.isEmpty) {
      setState(() {
        _paymentMethodId = '';
        _options = [];
      });
      return;
    }
    // GET /v1/payment_methods?bin=... NÃO filtra de verdade pelo bin (confirmado contra a API real) —
    // quem sabe a bandeira certa é o endpoint de parcelas, usado sempre (mesmo abaixo do limite de parcelamento).
    try {
      final amount = widget.amount > 0 ? widget.amount : 1;
      final r = await http.get(Uri.parse('https://api.mercadopago.com/v1/payment_methods/installments?bin=$bin&amount=$amount&public_key=${widget.publicKey}')).timeout(const Duration(seconds: 8));
      final list = jsonDecode(r.body) as List;
      final first = list.isNotEmpty ? list.first as Map : null;
      if (!mounted) return;
      setState(() => _paymentMethodId = first != null ? '${first['payment_method_id']}' : '');
      if (widget.amount > cardInstallmentsMinAmount) {
        final costs = first?['payer_costs'] as List? ?? const [];
        // só sem juros: a loja absorve a taxa, o preço nunca muda com a parcela escolhida
        final semJuros = [for (final p in costs) if (((p as Map)['installment_rate'] as num).toDouble() == 0) p['installments'] as int];
        setState(() => _options = semJuros);
      } else {
        setState(() => _options = []);
      }
    } catch (_) {
      if (mounted) setState(() {
        _paymentMethodId = '';
        _options = [];
      });
    }
  }

  /// Tokeniza o cartão (chamada direta ao Mercado Pago). `null` = algo não validou; já mostrou o aviso.
  Future<CardTokenResult?> tokenize() async {
    final number = onlyDigits(_number.text, 19);
    final expiry = onlyDigits(_expiry.text, 4);
    final cvv = onlyDigits(_cvv.text, 4);
    if (number.length < 13) {
      Toast.error('Confira o número do cartão.');
      return null;
    }
    if (expiry.length != 4) {
      Toast.error('Confira a validade do cartão.');
      return null;
    }
    if (cvv.length < 3) {
      Toast.error('Confira o código de segurança (CVV).');
      return null;
    }
    if (_name.text.trim().length < 2) {
      Toast.error('Informe o nome impresso no cartão.');
      return null;
    }
    if (!isValidCpf(_cpf.text)) {
      Toast.error('Informe um CPF válido (exigido pelo Mercado Pago para pagar com cartão).');
      return null;
    }
    if (_paymentMethodId.isEmpty) {
      Toast.error('Não reconhecemos a bandeira desse cartão.');
      return null;
    }
    try {
      final r = await http.post(
        Uri.parse('https://api.mercadopago.com/v1/card_tokens?public_key=${widget.publicKey}'),
        headers: const {'content-type': 'application/json'},
        body: jsonEncode({
          'card_number': number,
          'expiration_month': expiry.substring(0, 2),
          'expiration_year': '20${expiry.substring(2)}',
          'security_code': cvv,
          'cardholder': {'name': _name.text.trim(), 'identification': {'type': 'CPF', 'number': onlyDigits(_cpf.text, 11)}},
        }),
      ).timeout(const Duration(seconds: 15));
      final data = jsonDecode(r.body) as Map<String, dynamic>;
      if (r.statusCode >= 300 || data['id'] == null) {
        Toast.error('Confira os dados do cartão e tente de novo.');
        return null;
      }
      return CardTokenResult(cardToken: '${data['id']}', paymentMethodId: _paymentMethodId, cpf: onlyDigits(_cpf.text, 11), installments: _installments);
    } catch (_) {
      Toast.error('Não conseguimos falar com o Mercado Pago agora. Tente de novo.');
      return null;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      AppTextField(
        label: 'Número do cartão', controller: _number, keyboardType: TextInputType.number, maxLength: 23,
        formatters: [MaskFormatter(format: _formatCardNumber, warning: 'No número do cartão só entram números.')],
        onChanged: _onNumberChanged, autofillHints: const [AutofillHints.creditCardNumber],
      ),
      const SizedBox(height: 14),
      Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(
          child: AppTextField(
            label: 'Validade', controller: _expiry, hint: 'MM/AA', keyboardType: TextInputType.number, maxLength: 5,
            formatters: [MaskFormatter(format: _formatExpiry, warning: 'Na validade só entram números.')],
            autofillHints: const [AutofillHints.creditCardExpirationDate],
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: AppTextField(
            label: 'CVV', controller: _cvv, keyboardType: TextInputType.number, maxLength: 4,
            formatters: const [],
            autofillHints: const [AutofillHints.creditCardSecurityCode],
          ),
        ),
      ]),
      const SizedBox(height: 14),
      AppTextField(label: 'Nome impresso no cartão', controller: _name, capitalization: TextCapitalization.words, autofillHints: const [AutofillHints.creditCardName]),
      const SizedBox(height: 14),
      AppTextField(
        label: 'CPF', controller: _cpf, keyboardType: TextInputType.number, maxLength: 14,
        helper: 'Exigido pelo Mercado Pago para pagar com cartão. Não guardamos esse número.',
        formatters: [MaskFormatter(format: formatCpf, warning: 'No CPF só entram números.')],
      ),
      if (_options.isNotEmpty) ...[
        const SizedBox(height: 14),
        SelectField<int>(
          label: 'Parcelas', hint: 'Sem juros.', value: _installments,
          items: [for (final n in _options) DropdownMenuItem(value: n, child: Text('${n}x de ${money(widget.amount / n)}'))],
          onChanged: (v) => setState(() => _installments = v ?? 1),
        ),
      ],
    ]);
  }
}
