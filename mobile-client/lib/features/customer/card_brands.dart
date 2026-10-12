// Bandeiras de cartão que o Mercado Pago aceita nesta loja (vêm de GET /api/payment-config), mostradas antes de o
// cliente digitar o cartão. Sem bandeiras (consulta falhou ou servidor antigo), nada aparece: nunca inventamos lista.
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

/// Um meio de cartão aceito. [type] é credit_card, debit_card ou prepaid_card.
class CardBrand {
  const CardBrand({required this.id, required this.name, required this.type, required this.logo});

  factory CardBrand.fromJson(Map<String, dynamic> j) =>
      CardBrand(id: '${j['id']}', name: '${j['name']}', type: '${j['type']}', logo: '${j['logo']}');

  final String id, name, type, logo;
}

/// Lê a lista `brands` do payment-config; qualquer coisa fora do formato vira lista vazia.
List<CardBrand> parseBrands(Object? raw) {
  if (raw is! List) return const [];
  return [
    for (final b in raw)
      if (b is Map && b['logo'] is String && (b['logo'] as String).isNotEmpty) CardBrand.fromJson(Map<String, dynamic>.from(b)),
  ];
}

const _groups = [('credit_card', 'Crédito'), ('debit_card', 'Débito'), ('prepaid_card', 'Pré-pago')];

/// Faixa "Cartões aceitos": logos por tipo (crédito, débito, pré-pago), cada logo uma vez por tipo.
class AcceptedBrands extends StatelessWidget {
  const AcceptedBrands({super.key, required this.brands});
  final List<CardBrand> brands;

  @override
  Widget build(BuildContext context) {
    if (brands.isEmpty) return const SizedBox.shrink();
    final muted = Theme.of(context).colorScheme.onSurfaceVariant;
    final rows = <Widget>[];
    for (final (type, label) in _groups) {
      final seen = <String>{};
      final list = [for (final b in brands) if (b.type == type && seen.add(b.logo)) b];
      if (list.isEmpty) continue;
      rows.add(Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
          SizedBox(width: 64, child: Text(label, style: TextStyle(color: muted, fontSize: 12, fontWeight: FontWeight.w700))),
          Expanded(child: Wrap(spacing: 10, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [for (final b in list) _Logo(b)])),
        ]),
      ));
    }
    return Semantics(
      label: 'Cartões aceitos',
      child: Container(
        width: double.infinity,
        margin: const EdgeInsets.only(bottom: 14),
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
        decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest.withValues(alpha: .5), borderRadius: BorderRadius.circular(12)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: rows),
      ),
    );
  }
}

/// Logo da bandeira (PNG ou SVG); se não carregar, mostra o nome.
class _Logo extends StatelessWidget {
  const _Logo(this.brand);
  final CardBrand brand;

  @override
  Widget build(BuildContext context) {
    final name = Text(brand.name, style: const TextStyle(fontSize: 11));
    Widget fallback() => name;
    final image = brand.logo.toLowerCase().endsWith('.svg')
        ? SvgPicture.network(brand.logo, height: 22, width: 40, fit: BoxFit.contain, placeholderBuilder: (_) => const SizedBox(width: 40, height: 22))
        : Image.network(brand.logo, height: 22, width: 40, fit: BoxFit.contain, errorBuilder: (context, error, stack) => fallback());
    return Tooltip(message: brand.name, child: SizedBox(height: 22, width: 40, child: image));
  }
}
