import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/toast.dart';

import '../../core/biometric.dart';
import '../../core/push.dart';

import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/app_update.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// "Mais": metas, categorias, custos, tema e sair.
class MoreScreen extends StatelessWidget {
  const MoreScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final session = context.watch<Session>();
    final theme = context.watch<ThemeController>();
    final bio = context.watch<BiometricLock>();
    final push = context.watch<PushService>();
    final dark = Theme.of(context).brightness == Brightness.dark;
    Widget tile(
      IconData icon,
      String title,
      String subtitle,
      VoidCallback onTap,
    ) => Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: AppCard(
        onTap: onTap,
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: c.pinkTint,
                borderRadius: BorderRadius.circular(14),
              ),
              child: Icon(icon, color: c.pinkStrong),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 16,
                    ),
                  ),
                  Text(
                    subtitle,
                    style: TextStyle(color: c.muted, fontSize: 13),
                  ),
                ],
              ),
            ),
            Icon(Icons.chevron_right_rounded, color: c.muted),
          ],
        ),
      ),
    );
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
      children: [
        Row(
          children: [
            CircleAvatar(
              radius: 24,
              backgroundColor: c.pinkTint,
              child: Text(
                (session.ownerName ?? '?').characters.first.toUpperCase(),
                style: display(context, size: 22, color: c.pinkStrong),
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    session.ownerName ?? '',
                    style: display(context, size: 24),
                  ),
                  Text(
                    'Painel da confeiteira',
                    style: TextStyle(color: c.muted),
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 20),
        UpdateBanner(api: session.ownerApi, app: 'server'),
        tile(
          Icons.track_changes_rounded,
          'Metas de venda',
          'Defina o alvo do mês e acompanhe',
          () => context.push('/painel/metas'),
        ),
        tile(
          Icons.local_offer_outlined,
          'Cupons',
          'Códigos de desconto para os clientes',
          () => context.push('/painel/cupons'),
        ),
        tile(
          Icons.sell_outlined,
          'Categorias',
          'Ingredientes, receitas e gastos',
          () => context.push('/painel/categorias'),
        ),
        tile(
          Icons.restaurant_menu_outlined,
          'Cardápio',
          'O que aparece na landing page, ingredientes e combos',
          () => context.push('/painel/cardapio'),
        ),
        tile(
          Icons.qr_code_2_rounded,
          'Venda Presencial',
          'Pix estático por produto, funciona sem internet',
          () => context.push('/painel/venda-presencial'),
        ),
        tile(
          Icons.notifications_active_outlined,
          'Som de novos pedidos',
          push.sound.label,
          () => context.push('/painel/som'),
        ),
        tile(
          Icons.place_outlined,
          'Endereço de retirada',
          'Onde o cliente retira os pedidos',
          () => context.push('/painel/enderecos'),
        ),
        tile(
          Icons.storefront_outlined,
          'Fornecedores',
          'Sites e lojas onde você compra, com preços',
          () => context.push('/painel/fornecedores'),
        ),
        tile(
          Icons.undo_rounded,
          'Devoluções',
          'Pedidos de devolução e Pix devolvidos',
          () => context.push('/painel/devolucoes'),
        ),
        tile(
          Icons.reviews_outlined,
          'Feedback dos clientes',
          'Nota, mensagem e fotos enviadas no site e no app',
          () => context.push('/painel/feedback'),
        ),
        tile(
          Icons.insights_outlined,
          'Engajamento',
          'O que os clientes veem e até onde chegam no pedido',
          () => context.push('/painel/engajamento'),
        ),
        tile(
          Icons.privacy_tip_outlined,
          'Privacidade dos clientes',
          'Solicitações sobre dados (LGPD)',
          () => context.push('/painel/privacidade'),
        ),
        tile(
          Icons.settings_outlined,
          'Custos e ajustes',
          'Valor da hora e gastos do mês',
          () => context.push('/painel/custos'),
        ),
        AppCard(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
          child: SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text(
              'Tema escuro',
              style: TextStyle(fontWeight: FontWeight.w700),
            ),
            value: dark,
            activeTrackColor: c.pink,
            onChanged: (_) => theme.toggle(Theme.of(context).brightness),
          ),
        ),
        const SizedBox(height: 10),
        AppCard(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
          child: SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text(
              'Entrar com biometria',
              style: TextStyle(fontWeight: FontWeight.w700),
            ),
            subtitle: Text(
              bio.canUse ? 'Abre o app com a digital ou o rosto.' : 'Cadastre uma digital ou o rosto nas configurações do celular.',
              style: TextStyle(color: c.muted, fontSize: 13),
            ),
            value: bio.enabled,
            activeTrackColor: c.pink,
            onChanged: !bio.canUse && !bio.enabled
                ? null
                : (on) async {
                    final ok = on ? await bio.enable() : await bio.disable();
                    if (!ok)
                      Toast.error('Não foi possível confirmar a biometria.');
                  },
          ),
        ),
        const SizedBox(height: 10),
        const SizedBox(height: 6),
        BusyButton(
          label: 'Sair',
          icon: Icons.logout_rounded,
          outlined: true,
          onPressed: () async {
            await push.unregister(); // antes de sair: precisa da sessão para tirar este celular dos avisos
            await session.logoutOwner();
            if (context.mounted) context.go('/');
          },
        ),
        const SizedBox(height: 22),
        const VersionLabel('Raykookie gestão'),
      ],
    );
  }
}
