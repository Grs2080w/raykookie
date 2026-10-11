import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../core/api_client.dart';
import '../core/format.dart';
import 'art.dart';
import 'theme.dart';
import 'toast.dart';

// ---- estrutura -------------------------------------------------------------------------------------

class AppCard extends StatelessWidget {
  const AppCard({super.key, required this.child, this.padding = const EdgeInsets.all(16), this.onTap, this.color, this.borderColor});
  final Widget child;
  final EdgeInsets padding;
  final VoidCallback? onTap;
  final Color? color;
  final Color? borderColor;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return SizedBox(
      width: double.infinity,
      child: Material(
        color: color ?? c.surface,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24), side: BorderSide(color: borderColor ?? c.line)),
        clipBehavior: Clip.antiAlias,
        child: InkWell(onTap: onTap, child: Padding(padding: padding, child: child)),
      ),
    );
  }
}

class PageTitle extends StatelessWidget {
  const PageTitle(this.title, {super.key, this.subtitle});
  final String title;
  final String? subtitle;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: display(context, size: 32)),
          if (subtitle != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(subtitle!, style: TextStyle(color: context.c.muted))),
        ]),
      );
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.text, {super.key, this.action});
  final String text;
  final Widget? action;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(0, 20, 0, 10),
        child: Row(children: [Expanded(child: Text(text, style: display(context, size: 22))), if (action != null) action!]),
      );
}

class InfoBanner extends StatelessWidget {
  const InfoBanner(this.text, {super.key, this.icon = Icons.info_outline_rounded, this.tone});
  final String text;
  final IconData icon;
  final Color? tone;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final fg = tone ?? c.pinkStrong;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(color: tone == null ? c.pinkTint : tone!.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(16)),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(icon, size: 18, color: fg),
        const SizedBox(width: 10),
        Expanded(child: Text(text, style: TextStyle(color: fg, fontSize: 13.5, height: 1.35))),
      ]),
    );
  }
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.icon, required this.title, required this.text, this.action});
  final IconData icon;
  final String title, text;
  final Widget? action;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 40),
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(24), border: Border.all(color: c.line)),
      child: Column(children: [
        Container(width: 56, height: 56, decoration: BoxDecoration(color: c.pinkTint, borderRadius: BorderRadius.circular(16)), child: Icon(icon, color: c.pinkStrong, size: 26)),
        const SizedBox(height: 14),
        Text(title, textAlign: TextAlign.center, style: display(context, size: 20)),
        const SizedBox(height: 4),
        Text(text, textAlign: TextAlign.center, style: TextStyle(color: c.muted, height: 1.4)),
        if (action != null) ...[const SizedBox(height: 18), action!],
      ]),
    );
  }
}

// ---- badges e botões -------------------------------------------------------------------------------

class AppBadge extends StatelessWidget {
  const AppBadge(this.text, {super.key, this.tone = 'neutral', this.dot = true});
  final String text;
  final String tone; // ok | warn | bad | pink | neutral
  final bool dot;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final (bg, fg) = switch (tone) {
      'ok' => (c.ok.withValues(alpha: 0.14), c.ok),
      'warn' => (c.warn.withValues(alpha: 0.16), c.warn),
      'bad' => (c.bad.withValues(alpha: 0.12), c.bad),
      'pink' => (c.pinkTint, c.pinkStrong),
      _ => (c.surface2, c.muted),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(99)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        if (dot) Container(width: 6, height: 6, margin: const EdgeInsets.only(right: 6), decoration: BoxDecoration(color: fg, shape: BoxShape.circle)),
        Text(text, style: TextStyle(color: fg, fontSize: 12, fontWeight: FontWeight.w700)),
      ]),
    );
  }
}

/// Botão principal com estado de "ocupado" (evita toques repetidos).
class BusyButton extends StatelessWidget {
  const BusyButton({super.key, required this.label, required this.onPressed, this.busy = false, this.icon, this.expand = true, this.outlined = false});
  final String label;
  final VoidCallback? onPressed;
  final bool busy, expand, outlined;
  final IconData? icon;
  @override
  Widget build(BuildContext context) {
    final child = busy
        ? SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2.4, color: outlined ? context.c.pink : context.c.onPink))
        : Row(mainAxisSize: MainAxisSize.min, mainAxisAlignment: MainAxisAlignment.center, children: [
            if (icon != null) ...[Icon(icon, size: 19), const SizedBox(width: 8)],
            Flexible(child: Text(label, overflow: TextOverflow.ellipsis)),
          ]);
    final btn = outlined
        ? OutlinedButton(onPressed: busy ? null : onPressed, child: child)
        : FilledButton(onPressed: busy ? null : onPressed, child: child);
    return expand ? SizedBox(width: double.infinity, child: btn) : btn;
  }
}

// ---- campos ----------------------------------------------------------------------------------------

class LabeledField extends StatelessWidget {
  const LabeledField({super.key, required this.label, required this.child, this.hint});
  final String label;
  final String? hint;
  final Widget child;
  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Padding(padding: const EdgeInsets.only(bottom: 6), child: Text(label, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14))),
        child,
        if (hint != null) Padding(padding: const EdgeInsets.only(top: 5), child: Text(hint!, style: TextStyle(color: context.c.muted, fontSize: 12))),
      ]);
}

class AppTextField extends StatelessWidget {
  const AppTextField({
    super.key, required this.label, this.controller, this.hint, this.helper, this.keyboardType, this.maxLength, this.maxLines = 1,
    this.formatters, this.onChanged, this.obscure = false, this.enabled = true, this.suffix, this.capitalization = TextCapitalization.none,
    this.autofillHints, this.textInputAction, this.onSubmitted,
  });
  final String label;
  final TextEditingController? controller;
  final String? hint, helper;
  final TextInputType? keyboardType;
  final int? maxLength;
  final int maxLines;
  final List<TextInputFormatter>? formatters;
  final ValueChanged<String>? onChanged;
  final ValueChanged<String>? onSubmitted;
  final bool obscure, enabled;
  final Widget? suffix;
  final TextCapitalization capitalization;
  final Iterable<String>? autofillHints;
  final TextInputAction? textInputAction;

  @override
  Widget build(BuildContext context) => LabeledField(
        label: label,
        hint: helper,
        child: TextField(
          controller: controller, keyboardType: keyboardType, maxLength: maxLength, maxLines: obscure ? 1 : maxLines, enabled: enabled,
          obscureText: obscure, onChanged: onChanged, onSubmitted: onSubmitted, textCapitalization: capitalization, autofillHints: autofillHints,
          textInputAction: textInputAction,
          inputFormatters: [if (maxLength != null) LengthLimitingTextInputFormatter(maxLength), ...?formatters],
          decoration: InputDecoration(hintText: hint, counterText: '', suffixIcon: suffix),
        ),
      );
}

/// Só deixa passar dígitos, vírgula e ponto (ou só dígitos); o que sobra é descartado com um aviso amigável.
class NumberFormatter extends TextInputFormatter {
  NumberFormatter({this.integer = false});
  final bool integer;
  @override
  TextEditingValue formatEditUpdate(TextEditingValue old, TextEditingValue value) {
    final clean = sanitizeNumber(value.text, integer: integer);
    if (clean == value.text) return value;
    Toast.warn(integer ? 'Aqui só entram números inteiros, sem vírgula ou ponto.' : 'Aqui só entram números, vírgula e ponto. Ex.: 1.234,50');
    final removed = value.text.length - clean.length;
    final pos = (value.selection.baseOffset - removed).clamp(0, clean.length);
    return TextEditingValue(text: clean, selection: TextSelection.collapsed(offset: pos));
  }
}

/// Máscara de telefone ou CEP (só números).
class MaskFormatter extends TextInputFormatter {
  MaskFormatter({required this.format, required this.warning, this.allowed = r'[\d\s()\-]'});
  final String Function(String) format;
  final String warning;
  final String allowed;
  @override
  TextEditingValue formatEditUpdate(TextEditingValue old, TextEditingValue value) {
    final text = format(value.text);
    if (hasInvalidInput(old.text, value.text, text)) Toast.warn(warning);
    return TextEditingValue(text: text, selection: TextSelection.collapsed(offset: text.length));
  }

  /// Só o que o cliente acabou de digitar ou colar conta: os pontos, barras e traços que a própria máscara pôs
  /// ("123.456", "12/30") não são erro, nem os que vêm num valor colado já formatado. Mudança sem texto novo
  /// (o teclado fechando a composição ao trocar de campo) não avisa.
  bool hasInvalidInput(String before, String after, String formatted) {
    var start = 0;
    while (start < before.length && start < after.length && before[start] == after[start]) {
      start++;
    }
    var end = 0;
    while (end < before.length - start && end < after.length - start &&
        before[before.length - 1 - end] == after[after.length - 1 - end]) {
      end++;
    }
    final inserted = after.substring(start, after.length - end);
    final ok = RegExp(allowed);
    return inserted.split('').any((c) => !ok.hasMatch(c) && !formatted.contains(c));
  }
}

class NumberField extends StatelessWidget {
  const NumberField({super.key, required this.label, required this.controller, this.integer = false, this.hint, this.helper, this.onChanged, this.suffixText, this.maxLength = 15});
  final String label;
  final TextEditingController controller;
  final bool integer;
  final String? hint, helper, suffixText;
  final ValueChanged<String>? onChanged;
  final int maxLength;
  @override
  Widget build(BuildContext context) => AppTextField(
        label: label, controller: controller, hint: hint, helper: helper, maxLength: maxLength, onChanged: onChanged,
        keyboardType: integer ? TextInputType.number : const TextInputType.numberWithOptions(decimal: true),
        formatters: [NumberFormatter(integer: integer)],
        suffix: suffixText == null ? null : Padding(padding: const EdgeInsets.only(right: 14), child: Center(widthFactor: 1, child: Text(suffixText!, style: TextStyle(color: context.c.muted)))),
      );
}

class SelectField<T> extends StatelessWidget {
  const SelectField({super.key, required this.label, required this.value, required this.items, required this.onChanged, this.hint});
  final String label;
  final T value;
  final List<DropdownMenuItem<T>> items;
  final ValueChanged<T?> onChanged;
  final String? hint;
  @override
  Widget build(BuildContext context) => LabeledField(
        label: label,
        hint: hint,
        child: DropdownButtonFormField<T>(
          initialValue: value, items: items, onChanged: onChanged, isExpanded: true,
          decoration: const InputDecoration(), borderRadius: BorderRadius.circular(16), dropdownColor: context.c.surface,
        ),
      );
}

// ---- carregamento ----------------------------------------------------------------------------------

/// Carrega dados do servidor e mostra carregando / erro com "tentar de novo". Puxar para baixo recarrega.
class Loader<T> extends StatefulWidget {
  const Loader({super.key, required this.load, required this.builder, this.padding = const EdgeInsets.fromLTRB(16, 8, 16, 96), this.scroll = true});
  final Future<T> Function() load;
  final Widget Function(BuildContext context, T data, Future<void> Function() reload) builder;
  final EdgeInsets padding;

  /// false: a tela monta a própria rolagem (sem puxar para atualizar).
  final bool scroll;
  @override
  State<Loader<T>> createState() => LoaderState<T>();
}

class LoaderState<T> extends State<Loader<T>> {
  T? _data;
  Object? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    reload();
  }

  Future<void> reload() async {
    if (_data == null) setState(() { _loading = true; _error = null; });
    try {
      final d = await widget.load();
      if (!mounted) return;
      setState(() { _data = d; _error = null; _loading = false; });
    } catch (e) {
      if (!mounted) return;
      setState(() { _error = e; _loading = false; });
      if (_data != null) Toast.error(e is ApiException ? e.message : 'Não foi possível atualizar.');
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_data != null && !widget.scroll) return widget.builder(context, _data as T, reload);
    if (_data != null) {
      return RefreshIndicator(
        color: context.c.pink, onRefresh: reload,
        child: ListView(padding: widget.padding, physics: const AlwaysScrollableScrollPhysics(), children: [widget.builder(context, _data as T, reload)]),
      );
    }
    if (_loading) return Center(child: CircularProgressIndicator(color: context.c.pink));
    final msg = _error is ApiException ? (_error as ApiException).message : 'Algo deu errado. Tente de novo.';
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.cloud_off_rounded, size: 40, color: context.c.muted),
          const SizedBox(height: 12),
          Text(msg, textAlign: TextAlign.center, style: TextStyle(color: context.c.muted)),
          const SizedBox(height: 16),
          BusyButton(label: 'Tentar de novo', onPressed: reload, expand: false, icon: Icons.refresh_rounded),
        ]),
      ),
    );
  }
}

// ---- diálogos --------------------------------------------------------------------------------------

Future<bool> confirmDialog(BuildContext context, {required String title, required String message, String confirmLabel = 'Excluir'}) async {
  final c = context.c;
  final r = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title, style: display(ctx, size: 22)),
      content: Text(message, style: TextStyle(color: c.muted)),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text('Cancelar', style: TextStyle(color: c.ink))),
        FilledButton(style: FilledButton.styleFrom(backgroundColor: c.bad, foregroundColor: Colors.white, minimumSize: const Size(96, 44)), onPressed: () => Navigator.pop(ctx, true), child: Text(confirmLabel)),
      ],
    ),
  );
  return r ?? false;
}

/// Abre um formulário em folha inferior (equivale à gaveta lateral do web).
Future<T?> showFormSheet<T>(BuildContext context, {required String title, required Widget child}) {
  return showModalBottomSheet<T>(
    context: context, isScrollControlled: true, useSafeArea: true,
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
          Padding(padding: const EdgeInsets.only(bottom: 16), child: Text(title, style: display(ctx, size: 24))),
          child,
        ]),
      ),
    ),
  );
}

// ---- imagens ---------------------------------------------------------------------------------------

/// Foto da receita ou, sem foto, a ilustração padrão.
class PhotoTile extends StatelessWidget {
  const PhotoTile({super.key, required this.kind, this.url, this.headers, this.size, this.radius = 18, this.aspectRatio});
  final String kind;
  final String? url;
  final Map<String, String>? headers;
  final double? size, aspectRatio;
  final double radius;
  @override
  Widget build(BuildContext context) {
    final (a, b) = artColors[kind] ?? artColors['bancada']!;
    final art = Container(
      decoration: BoxDecoration(gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: [Color(a), Color(b)])),
      child: Center(child: FractionallySizedBox(heightFactor: 0.62, child: SvgPicture.string(artSvg(kind)))),
    );
    Widget inner = url == null
        ? art
        : Image.network(url!, headers: headers, fit: BoxFit.cover, errorBuilder: (_, _, _) => art, loadingBuilder: (_, child, p) => p == null ? child : art);
    inner = ClipRRect(borderRadius: BorderRadius.circular(radius), child: inner);
    if (aspectRatio != null) return AspectRatio(aspectRatio: aspectRatio!, child: inner);
    return SizedBox(width: size, height: size, child: inner);
  }
}

class RkLogo extends StatelessWidget {
  const RkLogo({super.key, this.variant = 'principal', this.tone, this.height = 36});
  final String variant; // principal | compacta | simbolo
  final String? tone; // fundo-claro | fundo-escuro | mono-claro | mono-escuro | sobre-rosa · null = pelo tema (claro: fundo-claro, escuro: mono-claro)
  final double height;
  @override
  Widget build(BuildContext context) {
    final t = tone ?? (Theme.of(context).brightness == Brightness.dark ? 'mono-claro' : 'fundo-claro');
    final file = variant == 'simbolo' ? 'raykookie-simbolo-cookie' : 'raykookie-$variant-$t';
    return SvgPicture.asset('assets/brand/$file.svg', height: height, semanticsLabel: 'Raykookie');
  }
}
