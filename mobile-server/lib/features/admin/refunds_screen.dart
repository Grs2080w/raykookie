import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/refund.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import '../../core/push.dart';

const _tabs = [('aguardando', 'Aguardando'), ('andamento', 'Em processamento'), ('concluidas', 'Concluídas'), ('negadas', 'Negadas')];
const _kind = {'automatico': 'Cancelamento com Pix devolvido', 'solicitado': 'Pedido de devolução', 'loja': 'Devolução da loja', 'sistema': 'Devolução automática'};
const _fail = {'saldo': 'Falta saldo no Mercado Pago', 'prazo': 'Prazo do Pix para devolução vencido', 'erro': 'Erro ao falar com o Mercado Pago'};

String _when(String iso) {
  final d = DateTime.tryParse(iso)?.toLocal();
  return d == null ? '' : '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')} ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
}

/// Devoluções: pedidos de devolução dos clientes (fotos, motivo) e devoluções de Pix em andamento.
class RefundsScreen extends StatefulWidget {
  const RefundsScreen({super.key});
  @override
  State<RefundsScreen> createState() => _RefundsScreenState();
}

class _RefundsScreenState extends State<RefundsScreen> {
  String _tab = 'aguardando';
  int _v = 0; // muda para recarregar a lista

  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    final c = context.c;
    return Scaffold(
      appBar: AppBar(title: const Text('Devoluções')),
      body: Loader<Map<String, dynamic>>(
        key: ValueKey('$_tab$_v${context.watch<PushService>().received}'),
        load: () async => Map<String, dynamic>.from(await api.get('/api/devolucoes?status=$_tab') as Map),
        builder: (context, d, reload) {
          final list = [for (final e in d['list'] as List) Map<String, dynamic>.from(e as Map)];
          final counts = Map<String, dynamic>.from(d['counts'] as Map);
          final pending = (d['pending_amount'] as num?)?.toDouble() ?? 0;
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const SizedBox(height: 8),
            if (pending > 0)
              Container(
                margin: const EdgeInsets.only(bottom: 10),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: c.warn.withValues(alpha: .12), borderRadius: BorderRadius.circular(14), border: Border.all(color: c.warn.withValues(alpha: .4))),
                child: Text('Há ${money(pending)} em devoluções em processamento. Mantenha esse valor disponível como saldo no Mercado Pago: sem ele a devolução não sai.'),
              ),
            Wrap(spacing: 8, runSpacing: 8, children: [
              for (final (id, label) in _tabs)
                ChoiceChip(
                  label: Text('$label${(counts[id] as num? ?? 0) > 0 ? ' · ${counts[id]}' : ''}'),
                  selected: _tab == id,
                  onSelected: (_) => setState(() => _tab = id),
                ),
            ]),
            const SizedBox(height: 12),
            if (list.isEmpty) Padding(padding: const EdgeInsets.only(top: 24), child: Center(child: Text('Nada por aqui.', style: TextStyle(color: c.muted)))),
            for (final r in list)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: AppCard(
                  onTap: () async {
                    final changed = await Navigator.of(context).push<bool>(MaterialPageRoute(builder: (_) => RefundDetailScreen(id: r['id'] as int)));
                    if (changed == true) setState(() => _v++);
                  },
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
                      Text('Pedido #${r['code']} · ${r['customer']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                      if (r['priority'] == true) Text('PRIORIDADE', style: TextStyle(color: c.bad, fontWeight: FontWeight.w800, fontSize: 12)),
                    ]),
                    Text('${_kind[r['kind']] ?? r['kind']} · ${reasonLabel('${r['reason_code']}')}', style: TextStyle(color: c.muted, fontSize: 13)),
                    if (r['status'] == 'falhou' && r['fail_reason'] != null) Text(_fail['${r['fail_reason']}'] ?? '', style: TextStyle(color: c.bad, fontWeight: FontWeight.w700, fontSize: 13)),
                    Text(
                      '${money(((r['approved_amount'] ?? r['requested_amount']) as num).toDouble())} · ${_when('${r['created_at']}')}${(r['photos'] as num) > 0 ? ' · ${r['photos']} foto(s)' : ''}',
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ]),
                ),
              ),
          ]);
        },
      ),
    );
  }
}

class RefundDetailScreen extends StatefulWidget {
  const RefundDetailScreen({super.key, required this.id});
  final int id;
  @override
  State<RefundDetailScreen> createState() => _RefundDetailScreenState();
}

class _RefundDetailScreenState extends State<RefundDetailScreen> {
  final _amount = TextEditingController(), _note = TextEditingController();
  String? _denyCode;
  bool _deny = false, _busy = false, _init = false;

  @override
  void dispose() {
    _amount.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _decide(Map<String, dynamic> r, bool approve) async {
    final api = context.read<Session>().ownerApi;
    setState(() => _busy = true);
    try {
      await api.post('/api/devolucoes/${widget.id}/decisao', approve ? {'approve': true, 'amount': _amount.text.trim(), 'note': _note.text.trim()} : {'approve': false, 'code': _denyCode, 'note': _note.text.trim()});
      Toast.ok(approve ? 'Aprovada: devolvendo o Pix' : 'Solicitação negada. O cliente vê o motivo.');
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _retry() async {
    final api = context.read<Session>().ownerApi;
    setState(() => _busy = true);
    try {
      final r = await api.post('/api/devolucoes/${widget.id}/tentar') as Map;
      if (r['status'] == 'concluido') {
        Toast.ok('Devolução concluída');
      } else {
        Toast.error('Ainda não foi possível. Veja o motivo no detalhe.');
      }
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = context.read<Session>();
    final api = session.ownerApi;
    final c = context.c;
    return Scaffold(
      appBar: AppBar(title: const Text('Devolução')),
      body: Loader<Map<String, dynamic>>(
        load: () async => Map<String, dynamic>.from(await api.get('/api/devolucoes/${widget.id}') as Map),
        builder: (context, r, reload) {
          final requested = (r['requested_amount'] as num).toDouble();
          final left = ((r['order_total'] as num) - (r['refunded_total'] as num)).toDouble();
          if (!_init) {
            _init = true;
            _amount.text = requested.toStringAsFixed(2).replaceAll('.', ',');
          }
          final status = '${r['status']}';
          final photos = [for (final p in r['photos'] as List) '${(p as Map)['url']}'];
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const SizedBox(height: 8),
            Text('Pedido #${r['code']} · ${r['customer']}', style: display(context, size: 22)),
            Text(formatPhone('${r['phone']}'), style: TextStyle(color: c.muted)),
            Text('${_kind[r['kind']] ?? r['kind']} · ${r['reason']}', style: TextStyle(color: c.muted)),
            Text('Pedido de ${money((r['order_total'] as num).toDouble())} · já devolvido ${money((r['refunded_total'] as num).toDouble())}', style: TextStyle(color: c.muted)),
            if (status == 'falhou')
              Container(
                margin: const EdgeInsets.only(top: 10),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: c.bad.withValues(alpha: .1), borderRadius: BorderRadius.circular(12)),
                child: Text('${_fail['${r['fail_reason']}'] ?? 'A devolução falhou'}. Tentativas: ${r['attempts']}.${r['next_try_at'] != null ? ' Nova tentativa automática em ${_when('${r['next_try_at']}')}.' : ' Toque em "Tentar de novo" depois de resolver.'}', style: TextStyle(color: c.bad)),
              ),
            if ('${r['description']}'.isNotEmpty)
              Container(margin: const EdgeInsets.only(top: 12), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: c.surface2, borderRadius: BorderRadius.circular(12)), child: Text('${r['description']}')),
            for (final i in r['items'] as List) Text('• ${(i as Map)['qty']}× ${i['name']}'),
            if (photos.isNotEmpty) ...[
              const SizedBox(height: 12),
              Wrap(spacing: 8, runSpacing: 8, children: [
                for (final (n, u) in photos.indexed)
                  GestureDetector(
                    onTap: () => showDialog<void>(
                      context: context,
                      builder: (_) => Dialog(child: InteractiveViewer(child: Image.network(api.absolute(u), headers: {'authorization': 'Bearer ${api.token}'}))),
                    ),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(12),
                      child: Image.network(api.absolute(u), headers: {'authorization': 'Bearer ${api.token}'}, width: 96, height: 96, fit: BoxFit.cover, semanticLabel: 'Foto ${n + 1} enviada pelo cliente'),
                    ),
                  ),
              ]),
            ],
            const SizedBox(height: 14),
            if (status == 'solicitado' && !_deny) ...[
              AppTextField(
                label: 'Valor a devolver (R\$). Pediu ${money(requested)}; ainda pode ${money(left)}',
                controller: _amount,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                formatters: [NumberFormatter()],
                onChanged: (_) => setState(() {}),
              ),
              AppTextField(label: 'Mensagem para a cliente (obrigatória se devolver menos do que ela pediu)', controller: _note, maxLength: 500),
              Row(children: [
                Expanded(child: OutlinedButton(onPressed: _busy ? null : () => setState(() => _deny = true), child: const Text('Negar'))),
                const SizedBox(width: 10),
                Expanded(child: BusyButton(label: parseNum(_amount.text) >= requested ? 'Aprovar total' : 'Aprovar parcial', busy: _busy, onPressed: () => _decide(r, true))),
              ]),
            ],
            if (status == 'solicitado' && !_deny)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text('Ao aprovar, o Pix é devolvido na hora e a cliente recebe uma notificação. Negar pede um motivo da lista.', style: TextStyle(color: c.muted, fontSize: 13)),
              ),
            if (status == 'solicitado' && _deny) ...[
              const Text('Motivo da negativa (a cliente vê)', style: TextStyle(fontWeight: FontWeight.w700)),
              RadioGroup<String>(
                groupValue: _denyCode,
                onChanged: (v) => setState(() => _denyCode = v),
                child: Column(children: [for (final d in denyReasons) RadioListTile<String>(dense: true, contentPadding: EdgeInsets.zero, value: d.id, title: Text(d.label))]),
              ),
              AppTextField(label: 'Explique à cliente', controller: _note, maxLength: 500),
              Row(children: [
                Expanded(child: OutlinedButton(onPressed: () => setState(() => _deny = false), child: const Text('Voltar'))),
                const SizedBox(width: 10),
                Expanded(child: BusyButton(label: 'Negar devolução', busy: _busy, onPressed: _denyCode == null ? null : () => _decide(r, false))),
              ]),
            ],
            if (status == 'falhou') BusyButton(label: 'Tentar de novo', busy: _busy, onPressed: _retry),
            if (status != 'solicitado' && '${r['decision_reason']}'.isNotEmpty) Text('Decisão: ${r['decision_reason']}. ${r['decision_note']}'),
            const SizedBox(height: 16),
            const Text('Histórico do pedido', style: TextStyle(fontWeight: FontWeight.w800)),
            for (final e in r['events'] as List) Text('${_when('${(e as Map)['at']}')} · ${_event['${e['type']}'] ?? e['type']}', style: TextStyle(color: c.muted, fontSize: 13)),
          ]);
        },
      ),
    );
  }
}

const _event = {
  'criado': 'Pedido criado', 'pix_gerado': 'Pix gerado', 'pago': 'Pagamento confirmado', 'status': 'Status alterado', 'cancelado': 'Pedido cancelado',
  'devolucao_solicitada': 'Devolução solicitada', 'devolucao_aprovada': 'Devolução aprovada', 'devolucao_negada': 'Devolução negada',
  'devolucao_enviada': 'Devolução enviada ao Mercado Pago', 'devolucao_concluida': 'Devolução concluída', 'devolucao_falhou': 'Devolução falhou', 'devolucao_desistiu': 'Cliente desistiu da solicitação',
};
