import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/app_update.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// Entrada do painel da confeiteira (mesmo login do /login do site).
class OwnerLoginScreen extends StatefulWidget {
  const OwnerLoginScreen({super.key});
  @override
  State<OwnerLoginScreen> createState() => _OwnerLoginScreenState();
}

class _OwnerLoginScreenState extends State<OwnerLoginScreen> {
  final _user = TextEditingController(), _pass = TextEditingController();
  bool _create = false, _show = false, _busy = false;

  @override
  void dispose() {
    _user.dispose();
    _pass.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final s = context.read<Session>();
    final username = _user.text.trim();
    if (username.isEmpty || _pass.text.isEmpty)
      return Toast.error('Informe usuário e senha.');
    if (_create &&
        !RegExp(r'^[\p{L}\p{N}_.-]{3,32}$', unicode: true).hasMatch(username)) {
      return Toast.error(
        'Use de 3 a 32 caracteres: letras, números, ponto, hífen ou sublinhado.',
      );
    }
    if (_create && _pass.text.length < 8)
      return Toast.error('A senha precisa ter pelo menos 8 caracteres.');
    setState(() => _busy = true);
    try {
      await (_create
          ? s.registerOwner(username, _pass.text)
          : s.loginOwner(username, _pass.text));
      if (mounted) context.go('/painel/inicio');
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Scaffold(
      appBar: AppBar(leading: context.canPop() ? const BackButton() : null),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 32),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const RkLogo(variant: 'compacta', business: true, height: 96),
                  const SizedBox(height: 28),
                  Text(
                    _create ? 'Crie sua conta' : 'Bem-vinda de volta',
                    style: display(context, size: 36),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    _create
                        ? 'Leva menos de um minuto. Só usuário e senha.'
                        : 'Entre para ver seu estoque e seus preços.',
                    style: TextStyle(color: c.muted),
                  ),
                  const SizedBox(height: 26),
                  AppTextField(
                    label: 'Usuário',
                    controller: _user,
                    maxLength: 32,
                    autofillHints: const [AutofillHints.username],
                  ),
                  const SizedBox(height: 16),
                  AppTextField(
                    label: 'Senha',
                    controller: _pass,
                    maxLength: 200,
                    obscure: !_show,
                    helper: _create ? 'Mínimo de 8 caracteres.' : null,
                    autofillHints: [
                      _create
                          ? AutofillHints.newPassword
                          : AutofillHints.password,
                    ],
                    onSubmitted: (_) => _submit(),
                    suffix: IconButton(
                      icon: Icon(
                        _show
                            ? Icons.visibility_off_outlined
                            : Icons.visibility_outlined,
                      ),
                      onPressed: () => setState(() => _show = !_show),
                    ),
                  ),
                  const SizedBox(height: 22),
                  BusyButton(
                    label: _create ? 'Criar conta' : 'Entrar',
                    busy: _busy,
                    onPressed: _submit,
                  ),
                  const SizedBox(height: 18),
                  Center(
                    child: Wrap(
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        Text(
                          _create ? 'Já tem conta? ' : 'Ainda não tem conta? ',
                          style: TextStyle(color: c.muted),
                        ),
                        GestureDetector(
                          onTap: () => setState(() => _create = !_create),
                          child: Text(
                            _create ? 'Entrar' : 'Criar conta',
                            style: TextStyle(
                              color: c.pinkStrong,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 18),
                  const VersionLabel('raykookie business'),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
