import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/cep.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// Endereços de retirada: a confeiteira guarda os que mais usa e escolhe qual vale agora.
/// O escolhido aparece para o cliente ao pedir (site e app) e fica gravado no pedido.
class PickupScreen extends StatelessWidget {
  const PickupScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Scaffold(
      appBar: AppBar(title: const Text('Endereço de retirada')),
      body: Loader<List<PickupAddress>>(
        load: () async => [
          for (final e in await api.get('/api/pickup-addresses') as List)
            PickupAddress.fromJson(e),
        ],
        builder: (context, list, reload) {
          final c = context.c;

          Future<void> edit([PickupAddress? a]) async {
            final saved = await showFormSheet<bool>(
              context,
              title: a == null ? 'Adicionar endereço' : 'Editar endereço',
              child: _PickupForm(
                existing: a,
                firstOne: list.isEmpty,
                hasActive: list.any((x) => x.isActive),
              ),
            );
            if (saved == true) await reload();
          }

          Future<void> choose(PickupAddress a) async {
            if (a.isActive) return;
            try {
              await api.patch('/api/pickup-addresses/${a.id}', {
                'active': true,
              });
              Toast.ok('Endereço de retirada atualizado');
              await reload();
            } on ApiException catch (e) {
              Toast.error(e.message);
            }
          }

          Future<void> remove(PickupAddress a) async {
            if (!await confirmDialog(
              context,
              title: 'Excluir “${a.label}”?',
              message: 'Os pedidos que já foram feitos mantêm o endereço que tinham.',
            ))
              return;
            try {
              await api.delete('/api/pickup-addresses/${a.id}');
              Toast.ok('Endereço removido');
              await reload();
            } on ApiException catch (e) {
              Toast.error(e.message);
            }
          }

          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 12),
              Text(
                'Escolha onde os clientes retiram os pedidos. O endereço atual aparece para o cliente ao pedir e no pedido dele. Guarde os que você mais usa e troque quando precisar.',
                style: TextStyle(color: c.muted, height: 1.4),
              ),
              const SizedBox(height: 16),
              BusyButton(
                label: 'Adicionar endereço',
                icon: Icons.add_rounded,
                onPressed: () => edit(),
              ),
              const SizedBox(height: 16),
              if (list.isEmpty)
                const EmptyState(
                  icon: Icons.place_outlined,
                  title: 'Nenhum endereço de retirada',
                  text: 'Adicione o endereço onde os clientes vão retirar os pedidos. Enquanto não houver um, o cliente vê só "Retirada no local".',
                )
              else
                for (final a in list)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: AppCard(
                      onTap: () => choose(a),
                      color: a.isActive ? c.pinkTint : null,
                      borderColor: a.isActive ? c.pink : null,
                      padding: const EdgeInsets.fromLTRB(12, 12, 4, 12),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Semantics(
                            inMutuallyExclusiveGroup: true,
                            selected: a.isActive,
                            label: 'Endereço de retirada atual',
                            child: Padding(
                              padding: const EdgeInsets.only(top: 2, right: 12),
                              child: Icon(
                                a.isActive
                                    ? Icons.radio_button_checked_rounded
                                    : Icons.radio_button_off_rounded,
                                color: a.isActive ? c.pink : c.muted,
                              ),
                            ),
                          ),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Flexible(
                                      child: Text(
                                        a.label,
                                        style: const TextStyle(
                                          fontWeight: FontWeight.w800,
                                          fontSize: 16,
                                        ),
                                      ),
                                    ),
                                    if (a.isActive) ...[
                                      const SizedBox(width: 8),
                                      const AppBadge(
                                        'Retirada atual',
                                        tone: 'pink',
                                        dot: false,
                                      ),
                                    ],
                                  ],
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  a.address,
                                  style: TextStyle(
                                    color: c.ink,
                                    fontSize: 14,
                                    height: 1.35,
                                  ),
                                ),
                                if (a.instructions.isNotEmpty)
                                  Padding(
                                    padding: const EdgeInsets.only(top: 4),
                                    child: Text(
                                      a.instructions,
                                      style: TextStyle(
                                        color: c.muted,
                                        fontSize: 13,
                                      ),
                                    ),
                                  ),
                              ],
                            ),
                          ),
                          IconButton(
                            tooltip: 'Editar ${a.label}',
                            icon: const Icon(Icons.edit_outlined),
                            onPressed: () => edit(a),
                          ),
                          IconButton(
                            tooltip: 'Excluir ${a.label}',
                            icon: const Icon(Icons.delete_outline_rounded),
                            onPressed: () => remove(a),
                          ),
                        ],
                      ),
                    ),
                  ),
            ],
          );
        },
      ),
    );
  }
}

class _PickupForm extends StatefulWidget {
  const _PickupForm({
    this.existing,
    required this.firstOne,
    required this.hasActive,
  });
  final PickupAddress? existing;
  final bool firstOne; // ainda não há nenhum endereço: este vira o atual
  final bool hasActive;
  @override
  State<_PickupForm> createState() => _PickupFormState();
}

class _PickupFormState extends State<_PickupForm> {
  late final _label = TextEditingController(text: widget.existing?.label ?? '');
  late final _cep = TextEditingController(
    text: formatCep(widget.existing?.cep ?? ''),
  );
  late final _street = TextEditingController(
    text: widget.existing?.street ?? '',
  );
  late final _number = TextEditingController(
    text: widget.existing?.number ?? '',
  );
  late final _complement = TextEditingController(
    text: widget.existing?.complement ?? '',
  );
  late final _district = TextEditingController(
    text: widget.existing?.district ?? '',
  );
  late final _city = TextEditingController(text: widget.existing?.city ?? '');
  late final _instructions = TextEditingController(
    text: widget.existing?.instructions ?? '',
  );
  late bool _makeActive = widget.firstOne;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [
      _label,
      _cep,
      _street,
      _number,
      _complement,
      _district,
      _city,
      _instructions,
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _lookup(String masked) async {
    final r = await lookupCep(onlyDigits(masked, 8));
    if (r == null || !mounted) return;
    setState(() {
      if (_street.text.isEmpty) _street.text = r.street;
      if (_district.text.isEmpty) _district.text = r.district;
      if (_city.text.isEmpty) _city.text = r.city;
    });
  }

  Future<void> _save() async {
    if (_street.text.trim().isEmpty) return Toast.error('Informe a rua.');
    if (_number.text.trim().isEmpty)
      return Toast.error('Informe o número (ou S/N, se não tiver).');
    if (_district.text.trim().isEmpty) return Toast.error('Informe o bairro.');
    if (_city.text.trim().isEmpty) return Toast.error('Informe a cidade.');
    final digits = onlyDigits(_cep.text, 8);
    if (digits.isNotEmpty && digits.length != 8)
      return Toast.error('O CEP precisa ter 8 números.');
    final api = context.read<Session>().ownerApi;
    final body = {
      'label': _label.text,
      'cep': digits,
      'street': _street.text,
      'number': _number.text,
      'complement': _complement.text,
      'district': _district.text,
      'city': _city.text,
      'instructions': _instructions.text,
    };
    setState(() => _busy = true);
    try {
      if (widget.existing == null) {
        await api.post('/api/pickup-addresses', {
          ...body,
          'active': _makeActive,
        });
      } else {
        await api.patch('/api/pickup-addresses/${widget.existing!.id}', {
          ...body,
          if (_makeActive) 'active': true,
        });
      }
      Toast.ok(
        widget.existing == null ? 'Endereço adicionado' : 'Endereço atualizado',
      );
      if (mounted) Navigator.pop(context, true);
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final alreadyActive = widget.existing?.isActive ?? false;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        AppTextField(
          label: 'Apelido do endereço',
          controller: _label,
          maxLength: 40,
          hint: 'Ex.: Ateliê',
          helper: 'Como você chama este lugar. Só você vê.',
          capitalization: TextCapitalization.words,
        ),
        const SizedBox(height: 14),
        AppTextField(
          label: 'CEP',
          controller: _cep,
          maxLength: 9,
          hint: '00000-000',
          keyboardType: TextInputType.number,
          formatters: [
            MaskFormatter(
              format: formatCep,
              warning: 'No CEP só entram números. Ex.: 01310-100',
              allowed: r'[\d\-]',
            ),
          ],
          onChanged: _lookup,
        ),
        const SizedBox(height: 14),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              flex: 3,
              child: AppTextField(
                label: 'Rua',
                controller: _street,
                maxLength: 100,
                capitalization: TextCapitalization.words,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              flex: 2,
              child: AppTextField(
                label: 'Número',
                controller: _number,
                maxLength: 10,
                hint: 'S/N',
              ),
            ),
          ],
        ),
        const SizedBox(height: 14),
        AppTextField(
          label: 'Complemento (opcional)',
          controller: _complement,
          maxLength: 60,
          hint: 'Sala, bloco…',
        ),
        const SizedBox(height: 14),
        AppTextField(
          label: 'Bairro',
          controller: _district,
          maxLength: 60,
          capitalization: TextCapitalization.words,
        ),
        const SizedBox(height: 14),
        AppTextField(
          label: 'Cidade',
          controller: _city,
          maxLength: 60,
          capitalization: TextCapitalization.words,
        ),
        const SizedBox(height: 14),
        AppTextField(
          label: 'Como chegar (opcional)',
          controller: _instructions,
          maxLength: 200,
          maxLines: 2,
          hint: 'Ex.: portão azul, tocar o interfone 2',
          helper: 'O cliente vê junto com o endereço.',
          capitalization: TextCapitalization.sentences,
        ),
        const SizedBox(height: 8),
        if (!alreadyActive)
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text(
              'Usar como endereço de retirada',
              style: TextStyle(fontWeight: FontWeight.w700),
            ),
            subtitle: Text(
              widget.firstOne
                  ? 'É o primeiro endereço, então já fica como o atual.'
                  : 'Troca o endereço que o cliente vê agora.',
              style: TextStyle(color: c.muted, fontSize: 13),
            ),
            value: _makeActive,
            activeTrackColor: c.pink,
            onChanged: widget.firstOne
                ? null
                : (v) => setState(() => _makeActive = v),
          ),
        const SizedBox(height: 12),
        BusyButton(label: 'Salvar endereço', busy: _busy, onPressed: _save),
      ],
    );
  }
}
