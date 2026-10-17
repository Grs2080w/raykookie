import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/core/analytics.dart';
import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/share_menu.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import '../../core/customer_push.dart';
import 'brand.dart';

/// Minha conta → Meus dados: onde o cliente exerce os direitos da LGPD (ver, corrigir, baixar, solicitar, excluir).
class MyDataScreen extends StatefulWidget {
  const MyDataScreen({super.key});
  @override
  State<MyDataScreen> createState() => _MyDataScreenState();
}

class _MyDataScreenState extends State<MyDataScreen> {
  Map<String, dynamic>? _d;
  String? _error;
  String _busy = '';
  final _name = TextEditingController(), _phone = TextEditingController(), _cep = TextEditingController(), _street = TextEditingController();
  final _number = TextEditingController(), _complement = TextEditingController(), _district = TextEditingController(), _city = TextEditingController();
  final _reqText = TextEditingController();
  String _reqType = 'anonimizacao';

  ApiClient get _api => context.read<Session>().customerApi;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  @override
  void dispose() {
    for (final c in [_name, _phone, _cep, _street, _number, _complement, _district, _city, _reqText]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final d = Map<String, dynamic>.from(await _api.get('/api/cliente/me/dados') as Map);
      if (!mounted) return;
      setState(() {
        _d = d;
        _error = null;
        _name.text = '${d['name']}';
        _phone.text = '${d['phone']}';
        _cep.text = '${d['cep']}';
        _street.text = '${d['street']}';
        _number.text = '${d['number']}';
        _complement.text = '${d['complement']}';
        _district.text = '${d['district']}';
        _city.text = '${d['city']}';
      });
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<void> _run(String key, Future<void> Function() fn) async {
    setState(() => _busy = key);
    try {
      await fn();
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = '');
    }
  }

  Future<void> _save() => _run('save', () async {
        await _api.patch('/api/cliente/me', {
          'name': _name.text, 'phone': _phone.text, 'cep': _cep.text, 'street': _street.text,
          'number': _number.text, 'complement': _complement.text, 'district': _district.text, 'city': _city.text,
        });
        Toast.ok('Dados atualizados');
        await _load();
      });

  Future<void> _export() => _run('export', () async {
        final json = const JsonEncoder.withIndent('  ').convert(await _api.get('/api/cliente/me/exportar'));
        await shareText(json, subject: 'Meus dados Raykookie', copiedMsg: 'Seus dados foram copiados');
      });

  Future<void> _request() => _run('req', () async {
        await _api.post('/api/cliente/me/privacidade', {'type': _reqType, 'details': _reqText.text});
        _reqText.clear();
        Toast.ok('Solicitação enviada');
        await _load();
      });

  Future<void> _unlink() => _run('google', () async {
        await _api.delete('/api/cliente/me/google');
        Toast.ok('Google desvinculado');
        await _load();
      });

  Future<void> _delete() async {
    final d = _d!;
    final confirm = TextEditingController(), password = TextEditingController();
    final go = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setS) => AlertDialog(
          title: const Text('Excluir minha conta?'),
          content: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Apagamos seu nome, e-mail, telefone, endereço e login. O registro dos pedidos e pagamentos é mantido, sem identificar você, porque a lei exige. Não dá para desfazer.'),
              const SizedBox(height: 12),
              AppTextField(label: 'Digite EXCLUIR para confirmar', controller: confirm, onChanged: (_) => setS(() {})),
              if (d['has_password'] == true) AppTextField(label: 'Sua senha', controller: password, obscure: true),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Voltar')),
            TextButton(
              onPressed: confirm.text.trim().toUpperCase() == 'EXCLUIR' ? () => Navigator.pop(ctx, true) : null,
              child: const Text('Excluir conta'),
            ),
          ],
        ),
      ),
    );
    if (go != true || !mounted) return;
    final session = context.read<Session>();
    final push = context.read<CustomerPush>();
    await _run('del', () async {
      await push.unregister(); // o backend também apaga os aparelhos; isto só evita esperar pelo próximo aviso
      await _api.delete('/api/cliente/me', {'confirm': confirm.text, 'password': password.text});
      await session.logoutCustomer();
      Toast.ok('Conta excluída');
      if (mounted) context.go('/');
    });
  }

  Widget _card(String title, List<Widget> children) => Container(
        margin: const EdgeInsets.only(top: 14),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(color: context.c.surface, borderRadius: BorderRadius.circular(18), border: Border.all(color: context.c.line)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: display(context, size: 20)),
          const SizedBox(height: 8),
          ...children,
        ]),
      );

  @override
  Widget build(BuildContext context) {
    return BrandScope(
      child: Builder(builder: (context) {
        final c = context.c;
        final d = _d;
        return Scaffold(
          appBar: const BrandAppBar(showBack: true, title: 'Meus dados'),
          body: _error != null
              ? Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(_error!)))
              : d == null
                  ? const Center(child: CircularProgressIndicator())
                  : ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 40), children: [
                      Text('Aqui você vê o que guardamos sobre você e exerce seus direitos da LGPD, sem precisar falar com ninguém.', style: TextStyle(color: c.muted)),
                      _card('Aparência', [
                        Material(
                          color: Colors.transparent,
                          child: SwitchListTile(
                            contentPadding: EdgeInsets.zero,
                            title: const Text('Tema escuro', style: TextStyle(fontWeight: FontWeight.w700)),
                            value: Theme.of(context).brightness == Brightness.dark,
                            activeTrackColor: c.pink,
                            onChanged: (_) => context.read<ThemeController>().toggle(Theme.of(context).brightness),
                          ),
                        ),
                      ]),
                      if (Analytics.instance.configured)
                        _card('Medição de uso', [
                          Material(
                            color: Colors.transparent,
                            child: SwitchListTile(
                              contentPadding: EdgeInsets.zero,
                              title: const Text('Ajudar a melhorar o cardápio', style: TextStyle(fontWeight: FontWeight.w700)),
                              subtitle: Text('Mede de forma anônima quais telas e itens são mais vistos, sem ligar a você nem guardar o que você digita.', style: TextStyle(color: c.muted, fontSize: 13)),
                              value: !Analytics.instance.optedOut,
                              activeTrackColor: c.pink,
                              onChanged: (on) async {
                                await Analytics.instance.setOptOut(!on);
                                if (mounted) setState(() {});
                              },
                            ),
                          ),
                        ]),
                      _card('O que guardamos sobre você', [
                        Text('E-mail: ${d['email']}'),
                        Text('Login: ${[if (d['has_password'] == true) 'e-mail e senha', if (d['has_google'] == true) 'Google'].join(' e ')}'),
                        Text('Pedidos: ${d['orders']}'),
                        const SizedBox(height: 6),
                        Text('Também guardamos o histórico dos seus pedidos (itens, valores, datas e pagamento) e, se você pediu devolução, as fotos enviadas.', style: TextStyle(color: c.muted, fontSize: 13)),
                        const SizedBox(height: 10),
                        BusyButton(label: 'Baixar meus dados', icon: Icons.download_rounded, outlined: true, busy: _busy == 'export', onPressed: _export),
                      ]),
                      _card('Corrigir meus dados', [
                        AppTextField(label: 'Nome', controller: _name, maxLength: 80),
                        AppTextField(label: 'Telefone', controller: _phone, keyboardType: TextInputType.phone),
                        AppTextField(label: 'CEP', controller: _cep, keyboardType: TextInputType.number),
                        AppTextField(label: 'Rua', controller: _street),
                        AppTextField(label: 'Número', controller: _number),
                        AppTextField(label: 'Complemento', controller: _complement),
                        AppTextField(label: 'Bairro', controller: _district),
                        AppTextField(label: 'Cidade', controller: _city),
                        Text('Para trocar o e-mail, faça uma solicitação abaixo, porque é com ele que você entra.', style: TextStyle(color: c.muted, fontSize: 12)),
                        const SizedBox(height: 10),
                        BusyButton(label: 'Salvar', busy: _busy == 'save', onPressed: _save),
                      ]),
                      _card('Com quem compartilhamos', [
                        const Text('• Mercado Pago: processa o pagamento por Pix (valor, seu e-mail e primeiro nome).'),
                        const Text('• Google: login com a conta Google, só se você escolher essa opção (nome e e-mail), e as notificações do app (Firebase: identificador do aparelho e o texto do aviso).'),
                        const Text('• ViaCEP: consulta o CEP para preencher o endereço (só o CEP é enviado).'),
                        const Text('• Hospedagem: provedores que mantêm o sistema no ar, com dever de confidencialidade.'),
                        const SizedBox(height: 6),
                        Text('Não vendemos seus dados.', style: TextStyle(color: c.muted, fontSize: 13)),
                        if (d['has_google'] == true) ...[
                          const SizedBox(height: 10),
                          const Text('Login com Google', style: TextStyle(fontWeight: FontWeight.w700)),
                          Text(
                            d['has_password'] == true
                                ? 'Você pode retirar a ligação da conta com o Google. Depois disso você entra só com e-mail e senha. Para revogar também a permissão no Google: myaccount.google.com/permissions.'
                                : 'Como esta conta não tem senha, a ligação só pode ser retirada se você excluir a conta ou fizer uma solicitação.',
                            style: TextStyle(color: c.muted, fontSize: 13),
                          ),
                          if (d['has_password'] == true) ...[
                            const SizedBox(height: 8),
                            BusyButton(label: 'Desvincular o Google', outlined: true, busy: _busy == 'google', onPressed: _unlink),
                          ],
                        ],
                      ]),
                      _card('Fazer uma solicitação sobre meus dados', [
                        Text('Anonimização, bloqueio ou eliminação de dados, revogação de consentimento ou outra dúvida. Respondemos em até ${d['deadline_days']} dias.', style: TextStyle(color: c.muted, fontSize: 13)),
                        const SizedBox(height: 10),
                        DropdownButtonFormField<String>(
                          initialValue: _reqType,
                          isExpanded: true,
                          items: [for (final t in (d['request_types'] as List)) DropdownMenuItem(value: '${t['id']}', child: Text('${t['label']}', overflow: TextOverflow.ellipsis))],
                          onChanged: (v) => setState(() => _reqType = v ?? _reqType),
                        ),
                        const SizedBox(height: 10),
                        AppTextField(label: 'Detalhes', controller: _reqText, maxLines: 3, maxLength: 1000),
                        BusyButton(label: 'Enviar solicitação', busy: _busy == 'req', onPressed: _request),
                        for (final r in (d['requests'] as List)) ...[
                          const SizedBox(height: 10),
                          Text('${r['type']} · ${r['status'] == 'aberta' ? 'em análise' : 'respondida'}', style: const TextStyle(fontWeight: FontWeight.w700)),
                          if ('${r['details']}'.isNotEmpty) Text('${r['details']}', style: TextStyle(color: c.muted)),
                          if ('${r['answer']}'.isNotEmpty) Text('Resposta: ${r['answer']}'),
                        ],
                      ]),
                      Container(
                        margin: const EdgeInsets.only(top: 14),
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(color: c.bad.withValues(alpha: .06), borderRadius: BorderRadius.circular(18), border: Border.all(color: c.bad.withValues(alpha: .3))),
                        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Text('Excluir minha conta', style: display(context, size: 20, color: c.bad)),
                          const SizedBox(height: 6),
                          const Text('Apagamos seu nome, e-mail, telefone, endereço e login. O registro dos pedidos e pagamentos é mantido, sem identificar você, porque a lei exige.'),
                          const SizedBox(height: 8),
                          if ((d['open_orders'] as num) > 0)
                            Text('Você tem ${d['open_orders']} pedido(s) em andamento. Depois que forem entregues (ou cancelados), é só voltar aqui.', style: const TextStyle(fontWeight: FontWeight.w700))
                          else
                            BusyButton(label: 'Excluir minha conta', icon: Icons.delete_outline_rounded, outlined: true, busy: _busy == 'del', onPressed: _delete),
                        ]),
                      ),
                    ]),
        );
      }),
    );
  }
}
