import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../core/api_client.dart';
import '../core/config.dart';
import 'theme.dart';
import 'widgets.dart';

/// Documentos legais (o texto vem do servidor, de shared/legal.ts, igual ao site).
const legalDocs = [
  ('termos', 'Termos de Serviço'),
  ('privacidade', 'Política de Privacidade'),
];

String legalLabel(String id) =>
    legalDocs.where((d) => d.$1 == id).map((d) => d.$2).firstOrNull ?? id;

/// Endereço da página do documento no site.
String legalUrl(String id) =>
    '${AppConfig.apiBase.replaceAll(RegExp(r'/$'), '')}/$id';

class LegalSection {
  LegalSection({
    required this.heading,
    this.paragraphs = const [],
    this.bullets = const [],
  });
  final String heading;
  final List<String> paragraphs, bullets;
  factory LegalSection.fromJson(Map<String, dynamic> j) => LegalSection(
    heading: (j['h'] ?? '').toString(),
    paragraphs: [for (final e in (j['p'] as List? ?? const [])) e.toString()],
    bullets: [for (final e in (j['ul'] as List? ?? const [])) e.toString()],
  );
}

class LegalDocument {
  LegalDocument({
    required this.id,
    required this.title,
    required this.summary,
    required this.updated,
    required this.sections,
  });
  final String id, title, summary, updated;
  final List<LegalSection> sections;
  factory LegalDocument.fromJson(Map<String, dynamic> j) => LegalDocument(
    id: (j['id'] ?? '').toString(),
    title: (j['title'] ?? '').toString(),
    summary: (j['summary'] ?? '').toString(),
    updated: (j['updated'] ?? '').toString(),
    sections: [
      for (final e in (j['sections'] as List? ?? const []))
        LegalSection.fromJson(e as Map<String, dynamic>),
    ],
  );
}

/// Tela de leitura dos Termos de Serviço e da Política de Privacidade.
/// Fica dentro do tema do app que a abre (a loja usa o tema da marca).
class LegalScreen extends StatelessWidget {
  const LegalScreen({super.key, required this.docId, required this.api});
  final String docId;
  final ApiClient api;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Scaffold(
      appBar: AppBar(
        title: Text(
          legalLabel(docId),
          style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 18),
        ),
      ),
      body: Loader<LegalDocument>(
        load: () async => LegalDocument.fromJson(
          await api.get('/api/legal/$docId') as Map<String, dynamic>,
        ),
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
        builder: (context, d, reload) => Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 720),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(d.title, style: display(context, size: 32)),
                const SizedBox(height: 8),
                Text(
                  d.summary,
                  style: TextStyle(color: c.muted, fontSize: 16, height: 1.4),
                ),
                const SizedBox(height: 6),
                Text(
                  'Última atualização: ${d.updated}',
                  style: TextStyle(color: c.muted, fontSize: 13),
                ),
                for (final s in d.sections) ...[
                  const SizedBox(height: 26),
                  Semantics(
                    header: true,
                    child: Text(
                      s.heading,
                      style: display(context, size: 21, height: 1.2),
                    ),
                  ),
                  for (final p in s.paragraphs)
                    Padding(
                      padding: const EdgeInsets.only(top: 10),
                      child: Text(
                        p,
                        style: const TextStyle(fontSize: 15.5, height: 1.5),
                      ),
                    ),
                  for (final b in s.bullets)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Padding(
                            padding: const EdgeInsets.only(
                              top: 8,
                              right: 10,
                              left: 4,
                            ),
                            child: Container(
                              width: 6,
                              height: 6,
                              decoration: BoxDecoration(
                                color: c.pink,
                                shape: BoxShape.circle,
                              ),
                            ),
                          ),
                          Expanded(
                            child: Text(
                              b,
                              style: const TextStyle(
                                fontSize: 15.5,
                                height: 1.5,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
                const SizedBox(height: 28),
                OutlinedButton.icon(
                  onPressed: () => launchUrl(
                    Uri.parse(legalUrl(d.id)),
                    mode: LaunchMode.externalApplication,
                  ),
                  icon: const Icon(Icons.open_in_new_rounded, size: 18),
                  label: const Text('Abrir no site'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Links "Termos de Serviço · Política de Privacidade" para rodapés e telas de cadastro.
class LegalLinks extends StatelessWidget {
  const LegalLinks({
    super.key,
    required this.onOpen,
    this.prefix,
    this.center = true,
  });
  final void Function(String docId) onOpen;

  /// Texto antes dos links, por exemplo "Ao criar sua conta, você concorda com os".
  final String? prefix;
  final bool center;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final style = TextStyle(color: c.muted, fontSize: 12.5, height: 1.5);
    final link = TextStyle(
      color: c.pinkStrong,
      fontSize: 12.5,
      fontWeight: FontWeight.w700,
      decoration: TextDecoration.underline,
    );
    Widget btn(String id, String label) => InkWell(
      onTap: () => onOpen(id),
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 44),
        child: Center(
          widthFactor: 1,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 6),
            child: Text(label, style: link),
          ),
        ),
      ),
    );
    return Column(
      crossAxisAlignment: center
          ? CrossAxisAlignment.center
          : CrossAxisAlignment.start,
      children: [
        if (prefix != null)
          Text(
            prefix!,
            style: style,
            textAlign: center ? TextAlign.center : TextAlign.start,
          ),
        Wrap(
          alignment: center ? WrapAlignment.center : WrapAlignment.start,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            btn('termos', 'Termos de Serviço'),
            Text('e', style: style),
            btn('privacidade', 'Política de Privacidade'),
          ],
        ),
      ],
    );
  }
}
