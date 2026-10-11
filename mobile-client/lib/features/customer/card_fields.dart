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

import 'mp_device.dart';

/// Aviso de pagamento em análise: deixa claro que nada foi cobrado ainda.
const cardInReview = 'Pagamento em análise pelo Mercado Pago. Nenhuma cobrança foi confirmada: se ele for recusado, '
    'nada é cobrado. Avisamos quando sair a resposta.';

/// Cartão que desconta na hora, à vista: débito ou pré-pago. O cartão de débito do Mercado Pago (o que usa o saldo
/// da conta, BIN 4174 01/02) vem do Mercado Pago como `prepaid_card`, não `debit_card`.
bool isDebitLike(String type) => type == 'debit_card' || type == 'prepaid_card';

/// Aviso quando a função escolhida ('credit_card' ou 'debit_card') não bate com o que o Mercado Pago diz do cartão
/// (`payment_type_id` pelo BIN; vazio = ainda não sabemos). Nulo = pode seguir.
String? cardTypeMismatch(String chosen, String detected) {
  if (detected.isEmpty) return null;
  if (chosen == 'debit_card') {
    return isDebitLike(detected) ? null : 'Pelo Mercado Pago, este cartão só passa no crédito. Escolha Crédito ou pague com Pix.';
  }
  if (detected == 'credit_card') return null;
  return detected == 'prepaid_card'
      ? 'Este cartão usa o saldo da conta (débito). Escolha Débito.'
      : 'Este é um cartão de débito. Escolha Débito.';
}

/// Mensagem para a recusa da tokenização do cartão (`POST /v1/card_tokens`), pelo código da causa.
String cardTokenError(Map<String, dynamic> body) {
  const byCode = {
    '205': 'Confira o número do cartão.', 'E301': 'Confira o número do cartão.',
    '208': 'Confira o mês da validade.', '325': 'Confira o mês da validade.',
    '209': 'Confira o ano da validade.', '326': 'Confira o ano da validade.',
    '224': 'Confira o código de segurança (CVV).', 'E302': 'Confira o código de segurança (CVV).', 'E203': 'Confira o código de segurança (CVV).',
    '221': 'Informe o nome impresso no cartão.', '316': 'Informe o nome impresso no cartão.',
    '214': 'Confira o CPF.', '324': 'Confira o CPF.', '213': 'Confira o CPF.',
  };
  final causes = body['cause'] is List ? body['cause'] as List : const [];
  for (final c in causes) {
    final code = c is Map ? '${c['code']}' : '';
    final message = byCode[code];
    if (message != null) return message;
  }
  return 'Confira os dados do cartão e tente de novo.';
}

class CardTokenResult {
  CardTokenResult({required this.cardToken, required this.paymentMethodId, required this.cpf, required this.installments, this.issuerId = '', this.deviceId = ''});
  final String cardToken, paymentMethodId, cpf;
  /// Emissor do cartão, como o Mercado Pago o reconheceu pelo BIN (vazio = não sabemos; a cobrança segue sem ele).
  final String issuerId;
  final int installments;
  /// Device ID do antifraude do Mercado Pago (vazio = não deu para obter; a cobrança segue sem ele).
  final String deviceId;
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
  String _issuerId = '';
  String _lastBin = '';
  /// O que o cliente escolheu: 'credit_card' ou 'debit_card'.
  String _type = 'credit_card';
  /// O que o Mercado Pago diz que o cartão é, pelo BIN (vazio = ainda não sabemos): credit_card, debit_card ou
  /// prepaid_card. Ele decide pelo BIN: cartão múltiplo (ex.: Nubank) só passa como crédito online.
  String _detectedType = '';
  /// Device ID do antifraude, lido em segundo plano enquanto o cliente preenche o cartão.
  String _deviceId = '';

  /// Aviso quando a escolha não bate com o cartão digitado (nulo = tudo certo).
  String? get _typeMismatch => cardTypeMismatch(_type, _detectedType);

  @override
  void dispose() {
    _number.dispose();
    _expiry.dispose();
    _cvv.dispose();
    _name.dispose();
    _cpf.dispose();
    super.dispose();
  }

  Future<void> _onNumberChanged(String v) => _lookupBin(v);

  /// Descobre bandeira, emissor, tipo e parcelas pelo BIN. `force` refaz a consulta do mesmo BIN (a anterior falhou).
  Future<void> _lookupBin(String v, {bool force = false}) async {
    final digits = onlyDigits(v, 19);
    final bin = digits.length >= 6 ? digits.substring(0, 6) : '';
    if (bin == _lastBin && !force) return;
    _lastBin = bin;
    if (bin.isEmpty) {
      setState(() {
        _paymentMethodId = '';
        _issuerId = '';
        _detectedType = '';
        _options = [];
        _installments = 1;
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
      // o cliente continuou digitando: esta resposta é de outro cartão
      if (!mounted || bin != _lastBin) return;
      final type = first != null ? '${first['payment_type_id']}' : '';
      final costs = first?['payer_costs'] as List? ?? const [];
      // só sem juros: a loja absorve a taxa, o preço nunca muda com a parcela escolhida
      final semJuros = widget.amount > cardInstallmentsMinAmount && type == 'credit_card'
          ? [for (final p in costs) if (((p as Map)['installment_rate'] as num).toDouble() == 0) p['installments'] as int]
          : <int>[];
      final issuer = first?['issuer'] is Map ? '${(first!['issuer'] as Map)['id'] ?? ''}' : '';
      setState(() {
        _paymentMethodId = first != null ? '${first['payment_method_id']}' : '';
        _issuerId = issuer == 'null' ? '' : issuer;
        _detectedType = type;
        // cartão que só existe no débito (ou pré-pago, como o do Mercado Pago): já marca Débito
        if (isDebitLike(type)) _type = 'debit_card';
        _options = semJuros;
        if (!semJuros.contains(_installments)) _installments = 1;
      });
    } catch (_) {
      if (!mounted || bin != _lastBin) return;
      // falhou (rede): esquece o BIN para o "Pagar" tentar de novo
      _lastBin = '';
      setState(() {
        _paymentMethodId = '';
        _issuerId = '';
        _detectedType = '';
        _options = [];
        _installments = 1;
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
    // a consulta da bandeira pode ter falhado (rede): tenta mais uma vez antes de desistir
    if (_paymentMethodId.isEmpty) await _lookupBin(number, force: true);
    if (!mounted) return null;
    if (_paymentMethodId.isEmpty) {
      Toast.error('Não reconhecemos a bandeira desse cartão. Confira o número ou tente outro cartão.');
      return null;
    }
    final mismatch = _typeMismatch;
    if (mismatch != null) {
      Toast.error(mismatch);
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
        Toast.error(cardTokenError(data));
        return null;
      }
      // débito é sempre à vista
      final installments = _type == 'debit_card' ? 1 : _installments;
      return CardTokenResult(cardToken: '${data['id']}', paymentMethodId: _paymentMethodId, cpf: onlyDigits(_cpf.text, 11), installments: installments, issuerId: _issuerId, deviceId: _deviceId);
    } catch (_) {
      Toast.error('Não conseguimos falar com o Mercado Pago agora. Tente de novo.');
      return null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final mismatch = _typeMismatch;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      LabeledField(
        label: 'Função do cartão',
        child: SizedBox(
          width: double.infinity,
          child: SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'credit_card', label: Text('Crédito'), icon: Icon(Icons.credit_card)),
              ButtonSegment(value: 'debit_card', label: Text('Débito'), icon: Icon(Icons.account_balance_wallet_outlined)),
            ],
            selected: {_type},
            onSelectionChanged: (v) => setState(() => _type = v.first),
          ),
        ),
      ),
      if (mismatch != null)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(mismatch, style: TextStyle(color: Theme.of(context).colorScheme.error, fontSize: 13.5, height: 1.35)),
        ),
      const SizedBox(height: 14),
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
      MpDeviceId(onDeviceId: (id) => _deviceId = id),
      if (_type == 'credit_card' && _options.isNotEmpty) ...[
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
