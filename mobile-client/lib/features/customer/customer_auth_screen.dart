import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/config.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/core/shop_rules.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/legal.dart';
import 'package:raykookie_common/ui/widgets.dart';
import 'brand.dart';

/// Quem de fato fala com o Google (os testes trocam por uma versão que não toca a plataforma nativa).
@visibleForTesting
Future<String?> Function(String serverClientId) googleAuthenticate = (serverClientId) async {
  await GoogleSignIn.instance.initialize(serverClientId: serverClientId);
  final account = await GoogleSignIn.instance.authenticate();
  return account.authentication.idToken;
};

/// Entrar ou criar conta para fazer pedidos: Google ou e-mail e senha.
class CustomerAuthScreen extends StatefulWidget {
  const CustomerAuthScreen({super.key, required this.next});
  final String next;
  @override
  State<CustomerAuthScreen> createState() => _CustomerAuthScreenState();
}

class _CustomerAuthScreenState extends State<CustomerAuthScreen> {
  bool _create = false, _show = false, _busy = false, _googleBusy = false;
  final _name = TextEditingController(), _email = TextEditingController(), _password = TextEditingController();

  @override
  void initState() {
    super.initState();
    // abriu o login já com sessão (por exemplo, um link antigo): vai direto para onde a pessoa queria
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && context.read<Session>().isCustomer) context.pushReplacement(widget.next);
    });
  }

  @override
  void dispose() {
    _name.dispose();
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final s = context.read<Session>();
    final email = _email.text.trim().toLowerCase();
    if (_create && _name.text.trim().length < 2) return Toast.error('Informe seu nome.');
    if (!isEmail(email)) return Toast.error('Esse e-mail não parece certo. Confira e tente de novo.');
    if (_create && _password.text.length < 8) return Toast.error('A senha precisa ter pelo menos 8 caracteres.');
    if (_password.text.isEmpty) return Toast.error('Informe sua senha.');
    setState(() => _busy = true);
    try {
      if (_create) {
        await s.registerCustomer(_name.text.trim(), email, _password.text);
      } else {
        await s.loginCustomer(email, _password.text);
      }
      if (mounted) context.pushReplacement(widget.next); // troca só o login: a tela de onde a pessoa veio continua embaixo (voltar funciona)
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _google() async {
    if (AppConfig.googleServerClientId.isEmpty) {
      Toast.warn('O login com o Google ainda não está disponível. Entre com e-mail e senha.');
      return;
    }
    final s = context.read<Session>();
    setState(() => _googleBusy = true);
    try {
      final idToken = await googleAuthenticate(AppConfig.googleServerClientId);
      if (idToken == null) throw ApiException(0, 'O Google não devolveu a identificação. Tente de novo.');
      await s.loginWithGoogle(idToken);
      if (mounted) context.pushReplacement(widget.next); // troca só o login: a tela de onde a pessoa veio continua embaixo (voltar funciona)
    } on ApiException catch (e) {
      Toast.error(e.message);
    } on GoogleSignInException catch (e) {
      if (e.code != GoogleSignInExceptionCode.canceled) Toast.error('Algo deu errado ao entrar com o Google. Tente de novo em instantes.');
    } catch (_) {
      Toast.error('Algo deu errado ao entrar com o Google. Tente de novo em instantes.');
    } finally {
      if (mounted) setState(() => _googleBusy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return BrandScope(
      child: Builder(builder: (context) {
        final c = context.c;
        return Scaffold(
          appBar: BrandAppBar(showBack: context.canPop()),
          body: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 24, 20, 40),
            child: Centered(
              maxWidth: 460,
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(_create ? 'Crie sua conta' : 'Entre para fazer seu pedido', style: display(context, size: 32, height: 1.1)),
                const SizedBox(height: 8),
                Text(_create ? 'Leva menos de um minuto. Depois você acompanha todos os seus pedidos aqui.' : 'Assim você acompanha seus pedidos e pede de novo sem digitar tudo outra vez.', style: TextStyle(color: c.muted, height: 1.4)),
                const SizedBox(height: 22),
                AppCard(
                  padding: const EdgeInsets.all(18),
                  child: Column(children: [
                    OutlinedButton(
                      onPressed: _googleBusy ? null : _google,
                      style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(52), backgroundColor: Colors.white),
                      child: _googleBusy
                          ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2.4))
                          : Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                              Container(width: 22, height: 22, alignment: Alignment.center, decoration: BoxDecoration(shape: BoxShape.circle, border: Border.all(color: c.line)), child: const Text('G', style: TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF4285F4)))),
                              const SizedBox(width: 12),
                              const Text('Continuar com o Google'),
                            ]),
                    ),
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      child: Row(children: [Expanded(child: Divider(color: c.line)), Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Text('ou com e-mail', style: TextStyle(color: c.muted, fontSize: 13))), Expanded(child: Divider(color: c.line))]),
                    ),
                    Container(
                      padding: const EdgeInsets.all(4),
                      decoration: BoxDecoration(color: c.surface2, borderRadius: BorderRadius.circular(16)),
                      child: Row(children: [
                        for (final (label, create) in [('Já tenho conta', false), ('Criar conta', true)])
                          Expanded(
                            child: GestureDetector(
                              onTap: () => setState(() => _create = create),
                              child: AnimatedContainer(
                                duration: const Duration(milliseconds: 180), height: 44, alignment: Alignment.center,
                                decoration: BoxDecoration(color: _create == create ? Colors.white : Colors.transparent, borderRadius: BorderRadius.circular(12)),
                                child: Text(label, style: TextStyle(fontWeight: FontWeight.w700, color: _create == create ? c.ink : c.muted)),
                              ),
                            ),
                          ),
                      ]),
                    ),
                    const SizedBox(height: 18),
                    if (_create) ...[AppTextField(label: 'Seu nome', controller: _name, maxLength: 80, capitalization: TextCapitalization.words, autofillHints: const [AutofillHints.name]), const SizedBox(height: 14)],
                    AppTextField(label: 'E-mail', controller: _email, maxLength: 120, keyboardType: TextInputType.emailAddress, autofillHints: const [AutofillHints.email]),
                    const SizedBox(height: 14),
                    AppTextField(
                      label: 'Senha', controller: _password, maxLength: 200, obscure: !_show, helper: _create ? 'Mínimo de 8 caracteres.' : null,
                      autofillHints: [_create ? AutofillHints.newPassword : AutofillHints.password], onSubmitted: (_) => _submit(),
                      suffix: IconButton(icon: Icon(_show ? Icons.visibility_off_outlined : Icons.visibility_outlined), onPressed: () => setState(() => _show = !_show), tooltip: _show ? 'Ocultar senha' : 'Mostrar senha'),
                    ),
                    const SizedBox(height: 18),
                    BusyButton(label: _create ? 'Criar conta e continuar' : 'Entrar', busy: _busy, onPressed: _submit),
                    const SizedBox(height: 10),
                    LegalLinks(prefix: _create ? 'Ao criar sua conta, você concorda com os' : 'Ao entrar, você concorda com os', onOpen: (id) => context.push('/$id')),
                  ]),
                ),
              ]),
            ),
          ),
        );
      }),
    );
  }
}
