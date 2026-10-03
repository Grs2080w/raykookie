import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// Fornecedores: sites com busca de preços (ex.: Romafele) e lojas físicas (cotação à mão).
class SuppliersScreen extends StatefulWidget {
  const SuppliersScreen({super.key});
  @override
  State<SuppliersScreen> createState() => _SuppliersScreenState();
}

class _SuppliersScreenState extends State<SuppliersScreen> {
  int _v = 0;

  Future<void> _add() async {
    final saved = await showFormSheet<bool>(context, title: 'Novo fornecedor', child: const _SupplierForm());
    if (saved == true) setState(() => _v++);
  }

  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    final c = context.c;
    return Scaffold(
      appBar: AppBar(title: const Text('Fornecedores')),
      floatingActionButton: FloatingActionButton.extended(onPressed: _add, icon: const Icon(Icons.add_rounded), label: const Text('Adicionar')),
      body: Loader<List<Map<String, dynamic>>>(
        key: ValueKey(_v),
        load: () async => [for (final e in await api.get('/api/suppliers') as List) Map<String, dynamic>.from(e as Map)],
        builder: (context, list, reload) {
          if (list.isEmpty) {
            return Padding(
              padding: const EdgeInsets.only(top: 40),
              child: Center(child: Text('Nenhum fornecedor ainda.\nExperimente cadastrar o site da Romafele:\nhttps://www.romafele.com.br', textAlign: TextAlign.center, style: TextStyle(color: c.muted))),
            );
          }
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const SizedBox(height: 8),
            Text('Nos sites com busca, o app procura os preços por você; nas lojas físicas, você guarda as cotações à mão.', style: TextStyle(color: c.muted, height: 1.4)),
            const SizedBox(height: 10),
            for (final s in list)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: AppCard(
                  child: Row(children: [
                    Icon(s['kind'] == 'site' ? Icons.language_rounded : Icons.storefront_outlined, color: c.pinkStrong),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${s['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                        Text(s['kind'] == 'site' ? '${s['url']}' : ('${s['address']}'.isEmpty ? 'Loja física' : '${s['address']}'), maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: c.muted, fontSize: 13)),
                        if (s['kind'] == 'site') Text(s['scraper'] != null ? 'Busca de preços ligada' : 'Só cotação manual', style: TextStyle(color: s['scraper'] != null ? c.ok : c.muted, fontSize: 12.5, fontWeight: FontWeight.w700)),
                        if ('${s['last_error']}'.isNotEmpty) Text('Precisa de revisão: ${s['last_error']}', style: TextStyle(color: c.bad, fontSize: 12.5)),
                      ]),
                    ),
                    IconButton(
                      onPressed: () async {
                        if (!await confirmDialog(context, title: 'Remover “${s['name']}”?', message: 'As cotações antigas continuam no histórico, sem o nome dele.', confirmLabel: 'Remover')) return;
                        try {
                          await api.delete('/api/suppliers/${s['id']}');
                          Toast.ok('Fornecedor removido');
                          setState(() => _v++);
                        } on ApiException catch (e) {
                          Toast.error(e.message);
                        }
                      },
                      icon: Icon(Icons.delete_outline_rounded, color: c.bad),
                      tooltip: 'Remover ${s['name']}',
                    ),
                  ]),
                ),
              ),
          ]);
        },
      ),
    );
  }
}

class _SupplierForm extends StatefulWidget {
  const _SupplierForm();
  @override
  State<_SupplierForm> createState() => _SupplierFormState();
}

class _SupplierFormState extends State<_SupplierForm> {
  final _name = TextEditingController(), _url = TextEditingController(), _address = TextEditingController();
  String _kind = 'site';
  bool _busy = false;

  @override
  void dispose() {
    _name.dispose();
    _url.dispose();
    _address.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _busy = true);
    try {
      await context.read<Session>().ownerApi.post('/api/suppliers', {'name': _name.text, 'kind': _kind, 'url': _url.text, 'address': _address.text});
      Toast.ok('Fornecedor cadastrado');
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        AppTextField(label: 'Nome', controller: _name, maxLength: 80),
        SegmentedButton<String>(
          segments: const [ButtonSegment(value: 'site', label: Text('Site')), ButtonSegment(value: 'loja', label: Text('Loja física'))],
          selected: {_kind},
          onSelectionChanged: (s) => setState(() => _kind = s.first),
        ),
        const SizedBox(height: 12),
        if (_kind == 'site')
          AppTextField(label: 'Endereço do site', controller: _url, hint: 'https://www.romafele.com.br', helper: 'Só sites públicos, com https. Lojas da plataforma Tray (como a Romafele) ganham busca de preços sozinha.', keyboardType: TextInputType.url)
        else
          AppTextField(label: 'Endereço (opcional)', controller: _address, maxLength: 200),
        const SizedBox(height: 8),
        BusyButton(label: 'Salvar', busy: _busy, onPressed: _save),
      ]);
}
