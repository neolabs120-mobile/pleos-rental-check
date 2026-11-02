import 'package:flutter/material.dart';
import '../app_scope.dart';
import '../ui/components.dart';
import 'login_screen.dart';
import 'signup_screen.dart';
import 'scan_link_screen.dart';
import 'settings_screen.dart';

class WelcomeScreen extends StatelessWidget {
  const WelcomeScreen({super.key});
  @override
  // Explain the shorter login-to-reservation flow before the user starts account setup or scans a vehicle QR.
  Widget build(BuildContext context) {
    final state = RentalScope.of(context);
    return AppPage(
      title: '렌트체크',
      actions: [
        IconButton(
          tooltip: '서버 설정',
          onPressed: () => openPage(context, const SettingsScreen()),
          icon: const Icon(Icons.settings_outlined),
        ),
      ],
      children: [
        const SizedBox(height: 16),
        const _WelcomeHero(),
        const Intro(
          '빌릴 때부터\n돌려줄 때까지, 확실하게',
          '차량 상태와 사진을 남기고\n나의 인수·반납 기록을 한곳에서 확인해요.',
        ),
        if (state.pendingLink != null)
          const Notice('차량 QR을 읽었어요. 가입·로그인 후 내 대여에서 연결을 이어가세요.'),
        if (state.startupError != null)
          Notice(state.startupError!, isError: true),
        const Panel(
          child: Column(
            children: [
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: _FeatureIcon(Icons.qr_code_scanner),
                title: Text('로그인하면 내 예약이 자동으로 표시돼요'),
              ),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: _FeatureIcon(Icons.photo_camera_outlined),
                title: Text('사진과 직접 확인으로 상태 기록'),
              ),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: _FeatureIcon(Icons.receipt_long_outlined),
                title: Text('접수 결과와 내 기록 보관'),
              ),
            ],
          ),
        ),
        PrimaryAction(
          '계정 만들기',
          onPressed: () => openPage(context, const SignupScreen()),
        ),
        OutlinedButton(
          onPressed: () => openPage(context, const LoginScreen()),
          child: const Padding(
            padding: EdgeInsets.all(14),
            child: Text('이미 계정이 있어요 · 로그인'),
          ),
        ),
        TextButton.icon(
          onPressed: () => openPage(context, const ScanLinkScreen()),
          icon: const Icon(Icons.qr_code_scanner),
          label: const Text('차량 QR 먼저 스캔하기'),
        ),
      ],
    );
  }
}

// Introduce the product with a layered teal card: the car mark, a check badge and the three stages the app connects.
// Content sizes the card (minimum 188), so enlarged text grows it instead of overflowing.
class _WelcomeHero extends StatelessWidget {
  const _WelcomeHero();
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final steps = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '인수부터 반납까지',
          style: Theme.of(context).textTheme.titleLarge?.copyWith(
            color: colors.onPrimary,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 18),
        for (final step in ['인수', '이용 중', '반납'])
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Container(
                  width: 7,
                  height: 7,
                  decoration: BoxDecoration(
                    color: colors.primaryContainer,
                    shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  step,
                  style: Theme.of(context).textTheme.labelLarge?.copyWith(
                    color: colors.onPrimary.withValues(alpha: .9),
                    fontSize: 14,
                  ),
                ),
              ],
            ),
          ),
      ],
    );
    final car = Stack(
      clipBehavior: Clip.none,
      children: [
        Icon(
          Icons.directions_car_rounded,
          size: 104,
          color: colors.onPrimary.withValues(alpha: .95),
        ),
        Positioned(
          right: -6,
          bottom: 2,
          child: CircleAvatar(
            radius: 18,
            backgroundColor: colors.primaryContainer,
            child: Icon(Icons.check_rounded, size: 22, color: colors.primary),
          ),
        ),
      ],
    );
    return Container(
      constraints: const BoxConstraints(minHeight: 188),
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(28),
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            colors.primary,
            Color.lerp(colors.primary, Colors.black, .28)!,
          ],
        ),
      ),
      child: Stack(
        children: [
          Positioned(
            right: -40,
            top: -50,
            child: CircleAvatar(
              radius: 110,
              backgroundColor: colors.onPrimary.withValues(alpha: .07),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 24, 30, 24),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(child: steps),
                const SizedBox(width: 12),
                car,
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// Render each welcome feature icon on a tinted rounded square so the list reads as a set rather than loose glyphs.
class _FeatureIcon extends StatelessWidget {
  const _FeatureIcon(this.icon);
  final IconData icon;
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      width: 42,
      height: 42,
      decoration: BoxDecoration(
        color: colors.primaryContainer,
        borderRadius: BorderRadius.circular(13),
      ),
      child: Icon(icon, color: colors.primary, size: 22),
    );
  }
}
