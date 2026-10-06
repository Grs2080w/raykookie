import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/widgets.dart';

String _when(String iso) {
  final d = DateTime.tryParse(iso)?.toLocal();
  if (d == null) return '';
  const meses = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'];
  return '${d.day} de ${meses[d.month - 1]} às ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
}

/// Um feedback na lista: nota, cliente, mensagem e fotos (toque para ampliar). Só leitura por enquanto.
class _FeedbackCard extends StatelessWidget {
  const _FeedbackCard(this.f, {required this.api});
  final Map<String, dynamic> f;
  final ApiClient api;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final rating = (f['rating'] as num).toInt();
    final photos = (f['photos'] as num).toInt();
    final message = '${f['message']}';
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              for (var s = 1; s <= 5; s++)
                Icon(s <= rating ? Icons.star_rounded : Icons.star_border_rounded, color: const Color(0xFFF0A020), size: 20),
              const Spacer(),
              Text(_when('${f['created_at']}'), style: TextStyle(color: c.muted, fontSize: 12)),
            ],
          ),
          const SizedBox(height: 6),
          Text('${f['customer_name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
          Text('${f['customer_email']}', style: TextStyle(color: c.muted, fontSize: 12)),
          if (message.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(message),
          ],
          if (photos > 0) ...[
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (var n = 1; n <= photos; n++)
                  GestureDetector(
                    onTap: () => showDialog<void>(
                      context: context,
                      builder: (_) => Dialog(
                        child: InteractiveViewer(
                          child: Image.network(api.absolute('/api/feedback/${f['id']}/fotos/$n'), headers: {'authorization': 'Bearer ${api.token}'}),
                        ),
                      ),
                    ),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(12),
                      child: Image.network(
                        api.absolute('/api/feedback/${f['id']}/fotos/$n'),
                        headers: {'authorization': 'Bearer ${api.token}'},
                        width: 96,
                        height: 96,
                        fit: BoxFit.cover,
                        semanticLabel: 'Foto $n do feedback',
                      ),
                    ),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

/// Feedback dos clientes (nota, mensagem e fotos), só leitura por enquanto.
class FeedbackScreen extends StatelessWidget {
  const FeedbackScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    final c = context.c;
    return Scaffold(
      appBar: AppBar(title: const Text('Feedback dos clientes')),
      body: Loader<List<Map<String, dynamic>>>(
        load: () async {
          final d = Map<String, dynamic>.from(await api.get('/api/feedback') as Map);
          return [for (final e in d['list'] as List) Map<String, dynamic>.from(e as Map)];
        },
        builder: (context, list, _) {
          if (list.isEmpty) {
            return Padding(
              padding: const EdgeInsets.only(top: 24),
              child: Center(child: Text('Nenhum feedback ainda.', style: TextStyle(color: c.muted))),
            );
          }
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (final f in list)
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: _FeedbackCard(f, api: api),
                ),
            ],
          );
        },
      ),
    );
  }
}
