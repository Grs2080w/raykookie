import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/toast.dart';

import '../../core/biometric.dart';
import '../../core/push.dart';
import 'menu_tree.dart';

import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/app_update.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// "Mais": o menu em seções (ver [menuSections]), tema, biometria e sair.
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
        const Align(alignment: Alignment.centerLeft, child: RkLogo(variant: 'principal', business: true, height: 44)),
        const SizedBox(height: 16),
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
        MenuTree(
          tile: (i) => tile(
            i.icon,
            i.title,
            i.route == '/painel/som' ? push.sound.label : i.subtitle,
            () => context.push(i.route),
          ),
        ),
        const SizedBox(height: 10),
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
        const VersionLabel('raykookie business'),
      ],
    );
  }
}
