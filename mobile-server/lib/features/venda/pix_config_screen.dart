import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import '../../core/pix/br_code.dart';
import '../../core/pix/venda_store.dart';

/// Chave Pix fixa da venda presencial. Fica só neste aparelho (não vai para o servidor).
class PixConfigScreen extends StatefulWidget {
  const PixConfigScreen({super.key});
  @override
  State<PixConfigScreen> createState() => _PixConfigScreenState();
}

class _PixConfigScreenState extends State<PixConfigScreen> {
  late PixKeyKind _kind;
  late final TextEditingController _key, _name, _city;
  String? _keyError;

  @override
  void initState() {
    super.initState();
    final cfg = context.read<VendaStore>().config;
    _kind = cfg?.kind ?? PixKeyKind.cpf;
    _key = TextEditingController(text: cfg?.key ?? '');
    _name = TextEditingController(text: cfg?.name ?? 'Raykookie');
    _city = TextEditingController(text: cfg?.city ?? '');
  }

  @override
  void dispose() {
    _key.dispose();
    _name.dispose();
    _city.dispose();
    super.dispose();
  }

  TextInputType get _keyboard => switch (_kind) {
    PixKeyKind.cpf || PixKeyKind.cnpj || PixKeyKind.phone => TextInputType.number,
    PixKeyKind.email => TextInputType.emailAddress,
    PixKeyKind.random => TextInputType.text,
  };

  Future<void> _save() async {
    final key = normalizePixKey(_kind, _key.text);
    final name = pixText(_name.text, 25), city = pixText(_city.text, 15);
    if (key == null) return setState(() => _keyError = 'Chave inválida para o tipo ${_kind.label}.');
    if (name.isEmpty || city.isEmpty) return Toast.error('Preencha o nome e a cidade.');
    final store = context.read<VendaStore>();
    final nav = Navigator.of(context);
    await store.saveConfig(PixConfig(kind: _kind, key: key, name: name, city: city));
    Toast.ok('Chave Pix salva neste celular');
    nav.pop();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Scaffold(
      appBar: AppBar(title: const Text('Chave Pix')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: [
          Text('Os QR Codes da venda presencial pagam direto para esta chave. Ela fica salva só neste celular.', style: TextStyle(color: c.muted, height: 1.4)),
          const SizedBox(height: 16),
          Wrap(
            spacing: 8,
            children: [
              for (final k in PixKeyKind.values)
                ChoiceChip(
                  label: Text(k.label),
                  selected: _kind == k,
                  onSelected: (_) => setState(() {
                    _kind = k;
                    _keyError = null;
                  }),
                ),
            ],
          ),
          const SizedBox(height: 14),
          TextField(
            controller: _key,
            keyboardType: _keyboard,
            autocorrect: false,
            onChanged: (_) => setState(() => _keyError = null),
            decoration: InputDecoration(labelText: 'Chave (${_kind.label})', errorText: _keyError),
          ),
          const SizedBox(height: 12),
          TextField(controller: _name, textCapitalization: TextCapitalization.words, maxLength: 25, decoration: const InputDecoration(labelText: 'Nome de quem recebe')),
          const SizedBox(height: 4),
          TextField(controller: _city, textCapitalization: TextCapitalization.words, maxLength: 15, decoration: const InputDecoration(labelText: 'Cidade')),
          const SizedBox(height: 18),
          BusyButton(label: 'Salvar', icon: Icons.check_rounded, onPressed: _save),
          if (context.watch<VendaStore>().config != null) ...[
            const SizedBox(height: 8),
            BusyButton(
              label: 'Remover chave',
              outlined: true,
              onPressed: () async {
                final nav = Navigator.of(context);
                await context.read<VendaStore>().clearConfig();
                nav.pop();
              },
            ),
          ],
        ],
      ),
    );
  }
}
