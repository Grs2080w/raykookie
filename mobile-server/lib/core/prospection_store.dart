import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:raykookie_common/core/prospection.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Uma simulação salva: nome, itens e parâmetros gerais.
class SavedSimulation {
  const SavedSimulation({required this.id, required this.name, required this.updatedAt, required this.items, required this.globals});
  final String id, name;
  final DateTime updatedAt;
  final List<ProspectItem> items;
  final ProspectGlobals globals;

  Map<String, dynamic> toJson() => {
    'id': id, 'name': name, 'updatedAt': updatedAt.toIso8601String(),
    'items': items.map((i) => i.toJson()).toList(), 'globals': globals.toJson(),
  };

  factory SavedSimulation.fromJson(Map<String, dynamic> j) => SavedSimulation(
    id: '${j['id']}',
    name: '${j['name'] ?? 'Sem nome'}',
    updatedAt: DateTime.tryParse('${j['updatedAt']}') ?? DateTime.fromMillisecondsSinceEpoch(0),
    items: _items(j['items']),
    globals: ProspectGlobals.fromJson(j['globals'] is Map ? Map<String, dynamic>.from(j['globals'] as Map) : {}),
  );
}

List<ProspectItem> _items(Object? l) => [
  for (final e in (l as List? ?? const [])) if (e is Map) ProspectItem.fromJson(Map<String, dynamic>.from(e)),
];

/// O estado da Prospecção. Vive acima das telas (Provider), então trocar de aba ou voltar do "Mais" não perde nada,
/// e é regravado no aparelho a cada mudança (sobrevive a fechar o app). Nada vai para o servidor nem altera o cadastro.
class ProspectionStore extends ChangeNotifier {
  ProspectionStore([this._prefs]) {
    _read();
  }

  static const _kDraft = 'prospeccao_rascunho', _kSaved = 'prospeccao_simulacoes';
  final SharedPreferences? _prefs; // nulo = só na memória (testes)

  List<ProspectItem> items = [];
  ProspectGlobals globals = const ProspectGlobals();
  String name = '';
  String? savedId;
  List<SavedSimulation> simulations = [];

  ProspectResult get result => calcProspect(items, globals);

  void _read() {
    try {
      final raw = _prefs?.getString(_kDraft);
      if (raw != null) {
        final j = Map<String, dynamic>.from(jsonDecode(raw) as Map);
        items = _items(j['items']);
        globals = ProspectGlobals.fromJson(j['globals'] is Map ? Map<String, dynamic>.from(j['globals'] as Map) : {});
        name = '${j['name'] ?? ''}';
        savedId = j['savedId'] as String?;
      }
    } catch (_) {} // dado estragado: começa do zero
    try {
      final raw = _prefs?.getString(_kSaved);
      if (raw != null) {
        simulations = [for (final e in jsonDecode(raw) as List) SavedSimulation.fromJson(Map<String, dynamic>.from(e as Map))];
        _sortSims();
      }
    } catch (_) {}
  }

  void _sortSims() => simulations.sort((a, b) => b.updatedAt.compareTo(a.updatedAt));

  void _changed() {
    _prefs?.setString(_kDraft, jsonEncode({'items': items.map((i) => i.toJson()).toList(), 'globals': globals.toJson(), 'name': name, 'savedId': savedId}));
    notifyListeners();
  }

  void _changedSims() {
    _prefs?.setString(_kSaved, jsonEncode(simulations.map((s) => s.toJson()).toList()));
    notifyListeners();
  }

  // ── rascunho
  void setGlobals(ProspectGlobals g) {
    globals = g;
    _changed();
  }

  void setName(String n) {
    name = n;
    _changed();
  }

  bool has(String id) => items.any((i) => i.id == id);

  void add(ProspectItem it) {
    if (!has(it.id)) items = [...items, it];
    _changed();
  }

  void addAll(Iterable<ProspectItem> list) {
    items = [...items, ...list.where((i) => !has(i.id))];
    _changed();
  }

  void remove(String id) {
    items = items.where((i) => i.id != id).toList();
    _changed();
  }

  void update(ProspectItem it) {
    items = [for (final i in items) i.id == it.id ? it : i];
    _changed();
  }

  void clearItems() {
    items = [];
    _changed();
  }

  /// Zera tudo para começar outra simulação (as salvas continuam).
  void startNew() {
    items = [];
    globals = const ProspectGlobals();
    name = '';
    savedId = null;
    _changed();
  }

  // ── simulações salvas
  /// Salva o rascunho; com [asCopy] (ou se ainda não foi salvo) cria uma nova. Devolve false se faltar o nome.
  bool save({bool asCopy = false, DateTime? now, String Function()? newId}) {
    final base = name.trim();
    if (base.isEmpty) return false;
    final stamp = now ?? DateTime.now();
    final title = asCopy ? '$base (cópia)' : base;
    final existing = !asCopy && savedId != null && simulations.any((s) => s.id == savedId);
    if (existing) {
      simulations = [for (final s in simulations) s.id == savedId ? SavedSimulation(id: s.id, name: title, updatedAt: stamp, items: items, globals: globals) : s];
    } else {
      final id = (newId ?? _uid)();
      simulations = [SavedSimulation(id: id, name: title, updatedAt: stamp, items: items, globals: globals), ...simulations];
      savedId = id;
      name = title;
    }
    _sortSims();
    _changedSims();
    _changed();
    return true;
  }

  void open(SavedSimulation s) {
    items = s.items;
    globals = s.globals;
    name = s.name;
    savedId = s.id;
    _changed();
  }

  void duplicate(SavedSimulation s, {DateTime? now, String Function()? newId}) {
    simulations = [SavedSimulation(id: (newId ?? _uid)(), name: '${s.name} (cópia)', updatedAt: now ?? DateTime.now(), items: s.items, globals: s.globals), ...simulations];
    _sortSims();
    _changedSims();
  }

  void delete(String id) {
    simulations = simulations.where((s) => s.id != id).toList();
    if (savedId == id) savedId = null;
    _changedSims();
    _changed();
  }

  static int _seq = 0;
  static String _uid() => '${DateTime.now().microsecondsSinceEpoch}-${_seq++}';

  /// Id de item novo (manual): o prefixo "m-" o distingue dos itens do cadastro ("r-" + id).
  static String newManualId() => 'm-${_uid()}';
}
