import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';

const _maxFeedbackPhotos = 5;
const _maxFeedbackPhotoBytes = 2000000;
const _maxFeedbackMessage = 1000;

/// Área de feedback: nota (1-5, obrigatória), mensagem e fotos opcionais. Usada na home e em Meus pedidos.
/// Espelha o FeedbackForm do site (mesmos limites, mesmo endpoint /api/cliente/feedback).
class FeedbackCard extends StatefulWidget {
  const FeedbackCard({super.key});
  @override
  State<FeedbackCard> createState() => _FeedbackCardState();
}

class _FeedbackCardState extends State<FeedbackCard> {
  int _rating = 0;
  final _message = TextEditingController();
  final _photos = <Uint8List>[];
  bool _busy = false, _sent = false;

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  Future<void> _pick() async {
    if (_photos.length >= _maxFeedbackPhotos) return Toast.warn('No máximo $_maxFeedbackPhotos fotos.');
    try {
      final x = await ImagePicker().pickImage(source: ImageSource.gallery, maxWidth: 1200, maxHeight: 1200, imageQuality: 82);
      if (x == null) return;
      final bytes = await x.readAsBytes();
      if (bytes.length > _maxFeedbackPhotoBytes) return Toast.warn('Essa foto é pesada demais (máximo de 2 MB). Escolha outra.');
      setState(() => _photos.add(bytes));
    } catch (_) {
      Toast.error('Não foi possível abrir a foto. Tente de novo.');
    }
  }

  Future<void> _send() async {
    if (_rating < 1) return Toast.error('Escolha uma nota de 1 a 5 estrelas.');
    setState(() => _busy = true);
    try {
      await context.read<Session>().customerApi.postMultipart(
        '/api/cliente/feedback',
        {'rating': '$_rating', 'message': _message.text.trim()},
        [for (final (n, b) in _photos.indexed) (name: 'fotos', filename: 'foto-${n + 1}.jpg', bytes: b, contentType: 'image/jpeg')],
      );
      if (mounted) setState(() => _sent = true);
      Toast.ok('Obrigado pelo feedback!');
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    if (_sent) {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(color: c.surface, borderRadius: BorderRadius.circular(24), border: Border.all(color: c.line)),
        child: Column(
          children: [
            const Text('Obrigado pelo feedback! 💛', style: TextStyle(fontWeight: FontWeight.w800)),
            const SizedBox(height: 4),
            Text('Sua opinião ajuda a gente a melhorar sempre.', style: TextStyle(color: c.muted, fontSize: 13)),
          ],
        ),
      );
    }
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(color: c.surface, borderRadius: BorderRadius.circular(24), border: Border.all(color: c.line)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('O que você achou?', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
          const SizedBox(height: 2),
          Text('Conte como foi sua experiência com a Raykookie.', style: TextStyle(color: c.muted, fontSize: 13)),
          const SizedBox(height: 10),
          Row(
            children: [
              for (var i = 1; i <= 5; i++)
                IconButton(
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(),
                  onPressed: () => setState(() => _rating = i),
                  icon: Icon(
                    i <= _rating ? Icons.star_rounded : Icons.star_border_rounded,
                    color: context.c.pink,
                    size: 30,
                  ),
                ),
            ],
          ),
          const SizedBox(height: 10),
          TextField(
            controller: _message,
            maxLength: _maxFeedbackMessage,
            maxLines: 3,
            decoration: const InputDecoration(hintText: 'Escreva aqui (opcional)'),
          ),
          Text('Fotos (opcional, até $_maxFeedbackPhotos)', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final (i, p) in _photos.indexed)
                Stack(
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(12),
                      child: Image.memory(p, width: 72, height: 72, fit: BoxFit.cover),
                    ),
                    Positioned(
                      right: -6,
                      top: -6,
                      child: GestureDetector(
                        onTap: () => setState(() => _photos.removeAt(i)),
                        child: CircleAvatar(radius: 12, backgroundColor: c.bad, child: const Icon(Icons.close, size: 14, color: Colors.white)),
                      ),
                    ),
                  ],
                ),
              if (_photos.length < _maxFeedbackPhotos)
                Material(
                  color: Colors.transparent,
                  borderRadius: BorderRadius.circular(12),
                  clipBehavior: Clip.antiAlias,
                  child: InkWell(
                    onTap: _pick,
                    child: Container(
                      width: 72,
                      height: 72,
                      decoration: BoxDecoration(border: Border.all(color: c.line, width: 2)),
                      child: Icon(Icons.camera_alt_outlined, color: c.muted),
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: _busy ? null : _send,
              child: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Enviar feedback'),
            ),
          ),
        ],
      ),
    );
  }
}
