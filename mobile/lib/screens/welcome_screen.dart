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
        Container(
          height: 170,
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primaryContainer,
            borderRadius: BorderRadius.circular(28),
          ),
          child: Stack(
            alignment: Alignment.center,
            children: [
              Icon(
                Icons.directions_car_rounded,
                size: 112,
                color: Theme.of(context).colorScheme.primary,
              ),
              const Positioned(
                right: 28,
                bottom: 18,
                child: CircleAvatar(
                  radius: 25,
                  child: Icon(Icons.check_rounded, size: 32),
                ),
              ),
            ],
          ),
        ),
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
                leading: Icon(Icons.qr_code_scanner),
                title: Text('로그인하면 내 예약이 자동으로 표시돼요'),
              ),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: Icon(Icons.photo_camera_outlined),
                title: Text('사진과 직접 확인으로 상태 기록'),
              ),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: Icon(Icons.receipt_long_outlined),
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
