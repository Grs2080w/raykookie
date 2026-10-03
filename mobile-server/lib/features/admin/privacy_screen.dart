import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

const _privacyLabels = {
  'acesso': 'Quero uma resposta sobre os meus dados',
  'correcao': 'Correção de dados',
  'anonimizacao': 'Anonimizar, bloquear ou eliminar dados',
  'portabilidade': 'Cópia dos dados',
  'compartilhamento': 'Saber com quem compartilharam meus dados',
  'revogacao': 'Revogar um consentimento',
  'exclusao': 'Exclusão da conta',
  'outro': 'Outro assunto',
};

/// Solicitações que os clientes fazem sobre os próprios dados (LGPD). A resposta tem prazo de 15 dias.
class PrivacyScreen extends StatelessWidget {
  const PrivacyScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Scaffold(
      appBar: AppBar(title: const Text('Privacidade dos clientes')),
      body: Loader<List<Map<String, dynamic>>>(
        load: () async => [for (final e in await api.get('/api/privacy-requests') as List) Map<String, dynamic>.from(e as Map)],
        builder: (context, list, reload) {
          final c = context.c;
          if (list.isEmpty) {
            return Padding(padding: const EdgeInsets.only(top: 40), child: Center(child: Text('Nenhuma solicitação até agora.', style: TextStyle(color: c.muted))));
          }
          Future<void> answer(Map<String, dynamic> r) async {
            final ctrl = TextEditingController();
            final text = await showDialog<String>(
              context: context,
              builder: (ctx) => AlertDialog(
                title: const Text('Responder ao cliente'),
                content: AppTextField(label: 'Resposta', controller: ctrl, maxLines: 4, maxLength: 1000),
                actions: [
                  TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancelar')),
                  TextButton(onPressed: () => Navigator.pop(ctx, ctrl.text), child: const Text('Concluir')),
                ],
              ),
            );
            if (text == null) return;
            try {
              await api.post('/api/privacy-requests/${r['id']}', {'answer': text});
              Toast.ok('Resposta enviada ao cliente');
              await reload();
            } on ApiException catch (e) {
              Toast.error(e.message);
            }
          }

          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const SizedBox(height: 8),
            for (final r in list)
              Container(
                margin: const EdgeInsets.only(bottom: 10),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(color: c.surface, borderRadius: BorderRadius.circular(16), border: Border.all(color: c.line)),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(_privacyLabels['${r['type']}'] ?? '${r['type']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                  Text('${r['customer']} · ${r['email']}', style: TextStyle(color: c.muted, fontSize: 13)),
                  Text(
                    r['status'] == 'aberta'
                        ? (DateTime.tryParse('${r['due_at']}')?.isBefore(DateTime.now()) == true ? 'Prazo vencido' : 'Responder em até 15 dias')
                        : 'Respondida',
                    style: TextStyle(fontWeight: FontWeight.w700, color: r['status'] == 'aberta' ? c.warn : c.ok, fontSize: 13),
                  ),
                  if ('${r['details']}'.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 6), child: Text('${r['details']}')),
                  if ('${r['answer']}'.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 6), child: Text('Resposta: ${r['answer']}')),
                  if (r['status'] == 'aberta') ...[
                    const SizedBox(height: 8),
                    BusyButton(label: 'Responder', outlined: true, expand: false, onPressed: () => answer(r)),
                  ],
                ]),
              ),
          ]);
        },
      ),
    );
  }
}
