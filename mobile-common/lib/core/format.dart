import 'package:intl/intl.dart';

final _brl = NumberFormat.currency(locale: 'pt_BR', symbol: r'R$');
final _brl4 = NumberFormat.currency(locale: 'pt_BR', symbol: r'R$', decimalDigits: 2)..maximumFractionDigits = 4;
final _num = NumberFormat.decimalPattern('pt_BR')..maximumFractionDigits = 3;
final _pct = NumberFormat.decimalPattern('pt_BR')..maximumFractionDigits = 1;

String money(num v) => _brl.format(v).replaceAll(' ', ' ');
String moneyPrecise(num v) => _brl4.format(v).replaceAll(' ', ' ');
String number(num v) => _num.format(v);
String percent(num v) => '${_pct.format(v)}%';

/// "2026-10-05" → "05/10/2026"
String dateBR(String? d) {
  if (d == null || d.length < 10) return '—';
  final p = d.substring(0, 10).split('-');
  return '${p[2]}/${p[1]}/${p[0]}';
}

String todayIso([DateTime? now]) {
  final n = now ?? DateTime.now();
  return '${n.year.toString().padLeft(4, '0')}-${n.month.toString().padLeft(2, '0')}-${n.day.toString().padLeft(2, '0')}';
}

String addDaysIso(String iso, int n) {
  final d = DateTime.utc(int.parse(iso.substring(0, 4)), int.parse(iso.substring(5, 7)), int.parse(iso.substring(8, 10))).add(Duration(days: n));
  return '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
}

const monthNames = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'];

/// Lê um número digitado em pt-BR (mesma regra do servidor):
///   "1.500,50" → 1500,5 · "1.000" → 1000 (ponto de milhar) · "7.5" → 7,5 · "0.500" → 0,5 · "12,5" → 12,5
/// Ponto seguido de exatamente 3 dígitos, sem vírgula e sem zero à esquerda, é milhar (1 kg = "1.000" g).
double? parseLocaleNumber(String raw) {
  final s = raw.trim();
  if (s.contains(',')) return double.tryParse(s.replaceAll('.', '').replaceAll(',', '.'));
  if (RegExp(r'^[1-9]\d{0,2}(\.\d{3})+$').hasMatch(s)) return double.tryParse(s.replaceAll('.', ''));
  return double.tryParse(s);
}

/// Como [parseLocaleNumber], mas texto inválido vale 0 (só para pré-visualizar).
double parseNum(String v) {
  final n = parseLocaleNumber(v);
  return (n == null || n.isNaN || n.isInfinite) ? 0 : n;
}

/// Só dígitos, "," (decimal, uma só) e "." (milhar). Com [integer], só dígitos. Depois da vírgula não entra ponto.
String sanitizeNumber(String raw, {bool integer = false}) {
  var out = raw.replaceAll(integer ? RegExp(r'[^\d]') : RegExp(r'[^\d.,]'), '');
  if (out.length > 15) out = out.substring(0, 15);
  if (!integer) {
    final i = out.indexOf(',');
    if (i >= 0) out = out.substring(0, i + 1) + out.substring(i + 1).replaceAll(RegExp(r'[.,]'), '');
  }
  return out;
}

String onlyDigits(String v, [int max = 20]) {
  final d = v.replaceAll(RegExp(r'\D'), '');
  return d.length > max ? d.substring(0, max) : d;
}

String formatPhone(String v) {
  final d = onlyDigits(v, 11);
  if (d.length <= 2) return d;
  if (d.length <= 6) return '(${d.substring(0, 2)}) ${d.substring(2)}';
  if (d.length <= 10) return '(${d.substring(0, 2)}) ${d.substring(2, 6)}-${d.substring(6)}';
  return '(${d.substring(0, 2)}) ${d.substring(2, 7)}-${d.substring(7)}';
}

String formatCep(String v) {
  final d = onlyDigits(v, 8);
  return d.length > 5 ? '${d.substring(0, 5)}-${d.substring(5)}' : d;
}

/// Número do servidor → texto do campo (sem ".0" sobrando).
String fieldNum(num v) => v == v.roundToDouble() ? v.toInt().toString() : v.toString().replaceAll('.', ',');
