import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/ui/legal.dart';

void main() {
  test('lê o documento que a API devolve', () {
    final d = LegalDocument.fromJson({
      'id': 'termos',
      'title': 'Termos de Serviço',
      'summary': 'Resumo',
      'updated': '2 de outubro de 2026',
      'sections': [
        {
          'h': '1. Quem somos',
          'p': ['Texto'],
        },
        {
          'h': '2. Serviços',
          'ul': ['a', 'b'],
        },
      ],
    });
    expect(d.sections.length, 2);
    expect(d.sections[0].paragraphs, ['Texto']);
    expect(d.sections[1].bullets, ['a', 'b']);
    expect(d.sections[1].paragraphs, isEmpty);
  });

  test('rótulos e endereço dos documentos', () {
    expect(legalLabel('termos'), 'Termos de Serviço');
    expect(legalLabel('privacidade'), 'Política de Privacidade');
    expect(legalUrl('privacidade'), endsWith('/privacidade'));
  });
}
