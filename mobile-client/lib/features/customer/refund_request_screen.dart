import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/refund.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';
import 'brand.dart';

/// Pedir devolução de um pedido pago cuja produção já começou: motivo, itens, descrição, fotos e revisão.
class RefundRequestScreen extends StatelessWidget {
  const RefundRequestScreen({super.key, required this.orderId});
  final int orderId;

  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().customerApi;
    return BrandScope(
      child: Scaffold(
        appBar: const BrandAppBar(showBack: true, title: 'Pedir devolução'),
        body: FutureBuilder<ShopOrder?>(
          future: () async {
            final list = [for (final e in await api.get('/api/cliente/pedidos') as List) ShopOrder.fromJson(e as Map<String, dynamic>)];
            return list.where((o) => o.id == orderId).firstOrNull;
          }(),
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
            final o = snap.data;
            if (snap.hasError || o == null) return const Center(child: Padding(padding: EdgeInsets.all(24), child: Text('Não conseguimos abrir este pedido agora. Tente de novo.')));
            if (o.refundMode != 'solicitacao') {
              return Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(o.refundWhyNot.isEmpty ? 'Este pedido não aceita pedido de devolução.' : o.refundWhyNot)));
            }
            return _RefundWizard(o);
          },
        ),
      ),
    );
  }
}

const refundSteps = ['Motivo', 'Itens', 'O que aconteceu', 'Fotos', 'Revisão'];

class _RefundWizard extends StatefulWidget {
  const _RefundWizard(this.o);
  final ShopOrder o;
  @override
  State<_RefundWizard> createState() => _RefundWizardState();
}

class _RefundWizardState extends State<_RefundWizard> {
  int _step = 0;
  Reason? _reason;
  final _qty = <String, int>{};
  final _desc = TextEditingController();
  final _photos = <Uint8List>[];
  bool _agree = false, _busy = false;

  @override
  void dispose() {
    _desc.dispose();
    super.dispose();
  }

  ShopOrder get o => widget.o;
  bool get _photoRequired => o.refundPhotosRequired && _reason?.photos == 'sim';
  bool get _photoStep => _reason != null && (_reason!.photos != 'nao' || _photos.isNotEmpty);
  double get _amount {
    var s = 0.0;
    for (final i in o.items) {
      s += (_qty[i.id] ?? 0) * i.unitPrice;
    }
    return s > o.refundMax ? o.refundMax : s;
  }

  /// Passos que existem para este motivo ("Fotos" some quando não há produto para fotografar).
  List<int> get _flow => [0, 1, 2, if (_photoStep) 3, 4];

  String? _check(int step) {
    switch (step) {
      case 0:
        return _reason == null ? 'Escolha o motivo.' : null;
      case 1:
        return _qty.values.every((v) => v == 0) ? 'Marque pelo menos um item com problema.' : null;
      case 2:
        return _desc.text.trim().length < minRefundDescription ? 'Conte o que aconteceu (pelo menos $minRefundDescription caracteres).' : null;
      case 3:
        return _photoRequired && _photos.isEmpty ? 'Envie pelo menos uma foto para mostrar o problema.' : null;
      case 4:
        return _agree ? null : 'Confirme que entendeu como funciona a análise.';
    }
    return null;
  }

  void _next() {
    final err = _check(_flow[_step]);
    if (err != null) return Toast.error(err);
    setState(() => _step++);
  }

  Future<void> _pick(ImageSource source) async {
    if (_photos.length >= maxRefundPhotos) return Toast.warn('No máximo $maxRefundPhotos fotos.');
    try {
      final x = await ImagePicker().pickImage(source: source, maxWidth: 1200, maxHeight: 1200, imageQuality: 82);
      if (x == null) return;
      final bytes = await x.readAsBytes();
      if (bytes.length > maxRefundPhotoBytes) return Toast.warn('Essa foto é pesada demais (máximo de 2 MB). Escolha outra ou tire uma nova.');
      setState(() => _photos.add(bytes));
    } catch (_) {
      Toast.error('Não foi possível abrir a foto. Tente de novo.');
    }
  }

  Future<void> _send() async {
    final err = _check(4);
    if (err != null) return Toast.error(err);
    setState(() => _busy = true);
    try {
      await context.read<Session>().customerApi.postMultipart(
        '/api/cliente/pedidos/${o.id}/devolucao',
        {
          'reason_code': _reason!.id,
          'description': _desc.text.trim(),
          'items': '[${[for (final i in o.items) if ((_qty[i.id] ?? 0) > 0) '{"id":"${i.id}","qty":${_qty[i.id]}}'].join(',')}]',
        },
        [for (final (n, b) in _photos.indexed) (name: 'fotos', filename: 'foto-${n + 1}.jpg', bytes: b, contentType: 'image/jpeg')],
      );
      Toast.ok('Solicitação enviada');
      if (mounted) context.pop();
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final flow = _flow;
    final at = flow[_step.clamp(0, flow.length - 1)];
    final last = _step >= flow.length - 1;
    return Column(children: [
      Expanded(
        child: ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 24), children: [
          Text('Pedido #${o.code}', style: display(context, size: 22)),
          const SizedBox(height: 4),
          Text('A confeiteira analisa e responde aqui mesmo, na tela do pedido.', style: TextStyle(color: c.muted)),
          const SizedBox(height: 14),
          Row(children: [
            Text('Passo ${_step + 1} de ${flow.length}', style: TextStyle(color: c.muted, fontSize: 13, fontWeight: FontWeight.w700)),
            const Spacer(),
            Text(refundSteps[at], style: TextStyle(color: c.muted, fontSize: 13, fontWeight: FontWeight.w700)),
          ]),
          const SizedBox(height: 8),
          Row(children: [
            for (var i = 0; i < flow.length; i++) ...[
              if (i > 0) const SizedBox(width: 6),
              Expanded(child: Container(height: 5, decoration: BoxDecoration(color: i <= _step ? c.pink : c.line, borderRadius: BorderRadius.circular(3)))),
            ],
          ]),
          const SizedBox(height: 16),
          if (at == 0) _motive(c),
          if (at == 1) _items(c),
          if (at == 2) _describe(c),
          if (at == 3) _photoStepView(c),
          if (at == 4) _review(c),
        ]),
      ),
      SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
          child: Row(children: [
            if (_step > 0) ...[
              OutlinedButton(onPressed: _busy ? null : () => setState(() => _step--), child: const Text('Voltar')),
              const SizedBox(width: 10),
            ],
            Expanded(child: BusyButton(label: last ? 'Enviar solicitação' : 'Continuar', busy: _busy, onPressed: last ? _send : _next)),
          ]),
        ),
      ),
    ]);
  }

  Widget _motive(AppColors c) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('Qual foi o problema?', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
        const SizedBox(height: 8),
        RadioGroup<String>(
          groupValue: _reason?.id,
          onChanged: (v) => setState(() {
            _reason = refundReasons.firstWhere((r) => r.id == v);
            if (_reason!.photos == 'nao' && _photos.isEmpty) _photos.clear();
          }),
          child: Column(children: [
            for (final r in refundReasons)
              RadioListTile<String>(contentPadding: EdgeInsets.zero, value: r.id, title: Text(r.label), subtitle: r.hint.isEmpty ? null : Text(r.hint, style: TextStyle(color: c.muted, fontSize: 12.5))),
          ]),
        ),
      ]);

  Widget _items(AppColors c) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('Quais itens tiveram o problema?', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
        const SizedBox(height: 8),
        for (final i in o.items)
          Container(
            margin: const EdgeInsets.only(bottom: 8),
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(color: c.surface, borderRadius: BorderRadius.circular(14), border: Border.all(color: c.line)),
            child: Row(children: [
              Expanded(child: Text('${i.name}\n${i.qty} no pedido · ${money(i.unitPrice)} cada', style: const TextStyle(height: 1.3))),
              IconButton.outlined(
                onPressed: (_qty[i.id] ?? 0) > 0 ? () => setState(() => _qty[i.id] = (_qty[i.id] ?? 0) - 1) : null,
                icon: const Icon(Icons.remove_rounded),
                tooltip: 'Menos ${i.name}',
              ),
              SizedBox(width: 28, child: Center(child: Text('${_qty[i.id] ?? 0}', style: const TextStyle(fontWeight: FontWeight.w800)))),
              IconButton.outlined(
                onPressed: (_qty[i.id] ?? 0) < i.qty ? () => setState(() => _qty[i.id] = (_qty[i.id] ?? 0) + 1) : null,
                icon: const Icon(Icons.add_rounded),
                tooltip: 'Mais ${i.name}',
              ),
            ]),
          ),
        Text('Valor que você está pedindo de volta: ${money(_amount)}', style: const TextStyle(fontWeight: FontWeight.w700)),
      ]);

  Widget _describe(AppColors c) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('Conte o que aconteceu', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
        const SizedBox(height: 8),
        AppTextField(
          label: 'Descrição',
          controller: _desc,
          maxLines: 5,
          maxLength: 1000,
          helper: 'Mínimo de $minRefundDescription caracteres. Quanto mais detalhes, mais rápida a análise.',
          onChanged: (_) => setState(() {}),
        ),
      ]);

  Widget _photoStepView(AppColors c) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(_photoRequired ? 'Fotos (obrigatório: de 1 a $maxRefundPhotos)' : 'Fotos (opcional, até $maxRefundPhotos)', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
        const SizedBox(height: 4),
        Text('Mostre o produto e a embalagem. Só a confeiteira vê as fotos, e a localização escondida nelas é removida antes do envio.', style: TextStyle(color: c.muted)),
        const SizedBox(height: 10),
        Wrap(spacing: 10, runSpacing: 10, children: [
          for (final (n, b) in _photos.indexed)
            Stack(clipBehavior: Clip.none, children: [
              ClipRRect(borderRadius: BorderRadius.circular(12), child: Image.memory(b, width: 84, height: 84, fit: BoxFit.cover, semanticLabel: 'Foto ${n + 1}')),
              Positioned(
                right: -8,
                top: -8,
                child: IconButton.filled(
                  onPressed: () => setState(() => _photos.removeAt(n)),
                  icon: const Icon(Icons.close_rounded, size: 16),
                  tooltip: 'Remover foto ${n + 1}',
                  style: IconButton.styleFrom(backgroundColor: c.bad, foregroundColor: Colors.white, minimumSize: const Size(32, 32)),
                ),
              ),
            ]),
        ]),
        const SizedBox(height: 12),
        if (_photos.length < maxRefundPhotos)
          Row(children: [
            Expanded(child: OutlinedButton.icon(onPressed: () => _pick(ImageSource.camera), icon: const Icon(Icons.photo_camera_outlined), label: const Text('Câmera'))),
            const SizedBox(width: 10),
            Expanded(child: OutlinedButton.icon(onPressed: () => _pick(ImageSource.gallery), icon: const Icon(Icons.photo_library_outlined), label: const Text('Galeria'))),
          ]),
        if (_photoRequired && _photos.isEmpty)
          Container(
            margin: const EdgeInsets.only(top: 12),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(color: c.warn.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(14)),
            child: Text('Sem foto não dá para continuar: o produto já foi entregue e a foto é a comprovação.', style: TextStyle(color: c.ink, fontSize: 13.5)),
          ),
      ]);

  Widget _review(AppColors c) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('Revise e envie', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
        const SizedBox(height: 8),
        AppCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Motivo: ${_reason?.label ?? ''}'),
            const SizedBox(height: 4),
            for (final i in o.items) if ((_qty[i.id] ?? 0) > 0) Text('${_qty[i.id]}× ${i.name}'),
            const SizedBox(height: 4),
            Text('Valor pedido: ${money(_amount)}', style: const TextStyle(fontWeight: FontWeight.w800)),
            const SizedBox(height: 4),
            Text(_desc.text.trim(), style: TextStyle(color: c.muted)),
            const SizedBox(height: 4),
            Text('${_photos.length} foto${_photos.length == 1 ? '' : 's'}'),
          ]),
        ),
        CheckboxListTile(
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
          value: _agree,
          onChanged: (v) => setState(() => _agree = v == true),
          title: const Text('Entendo que, como o pedido já entrou em produção, a devolução não é garantida: a confeiteira analisa e decide, podendo devolver o valor total, parte dele ou negar.', style: TextStyle(fontSize: 14)),
        ),
        Text('Você recebe uma notificação quando houver resposta.', style: TextStyle(color: c.muted, fontSize: 13)),
      ]);
}
