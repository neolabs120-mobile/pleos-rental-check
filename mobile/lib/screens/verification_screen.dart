import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../app_scope.dart';
import '../data/rental_api.dart';
import '../models/rental_models.dart';
import '../ui/components.dart';
import 'login_screen.dart';

class VerificationScreen extends StatefulWidget {
  const VerificationScreen({super.key, required this.challenge});
  final EmailChallenge challenge;
  @override
  State<VerificationScreen> createState() => _VerificationScreenState();
}

class _VerificationScreenState extends State<VerificationScreen>
    with ActionState {
  late EmailChallenge challenge = widget.challenge;
  late final Timer ticker;
  final form = GlobalKey<FormState>();
  final code = TextEditingController(),
      password = TextEditingController(),
      confirm = TextEditingController();
  @override
  void initState() {
    super.initState();
    ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (context.mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    ticker.cancel();
    for (final c in [code, password, confirm]) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final remaining = challenge.expiresAt.difference(DateTime.now()).inSeconds;
    final cooldown = challenge.resendAt.difference(DateTime.now()).inSeconds;
    return AppPage(
      title: challenge.isReset ? '새 비밀번호 설정' : '이메일 확인',
      children: [
        Intro(
          challenge.isReset ? '새 비밀번호를 입력해 주세요' : '이메일을 확인해 주세요',
          challenge.email,
        ),
        Notice(
          challenge.fileDelivery
              ? '개발 모드: 실제 메일은 발송되지 않았어요. 확인번호는 서버의 비공개 개발 메일함에서 확인해 주세요.'
              : challenge.isReset
              ? '가입한 이메일이면 확인번호를 보냈어요.'
              : '이메일로 보낸 6자리 확인번호를 입력해 주세요.',
        ),
        Text(
          remaining > 0
              ? '확인 시간 ${remaining ~/ 60}:${(remaining % 60).toString().padLeft(2, '0')}'
              : '확인 시간이 지났어요. 처음부터 다시 요청해 주세요.',
        ),
        Form(
          key: form,
          child: Column(
            children: [
              TextFormField(
                controller: code,
                keyboardType: TextInputType.number,
                maxLength: 6,
                autofillHints: const [AutofillHints.oneTimeCode],
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: const InputDecoration(labelText: '확인번호 6자리'),
                validator: (v) =>
                    v != null && v.length == 6 ? null : '확인번호 6자리를 입력해 주세요.',
              ),
              if (challenge.isReset) ...[
                const SizedBox(height: 18),
                PasswordField(controller: password, newPassword: true),
                const SizedBox(height: 18),
                PasswordField(
                  controller: confirm,
                  label: '비밀번호 확인',
                  newPassword: true,
                  confirm: password,
                ),
              ],
            ],
          ),
        ),
        if (error != null) Notice(error!, isError: true),
        PrimaryAction(
          challenge.isReset ? '비밀번호 변경' : '확인하고 가입 완료',
          busy: busy,
          onPressed: remaining <= 0
              ? null
              : () {
                  if (!form.currentState!.validate()) return;
                  run(() async {
                    await RentalScope.of(context).api.request(
                      'POST',
                      challenge.isReset
                          ? '/api/auth/password/reset'
                          : '/api/auth/verify',
                      auth: Auth.none,
                      body: {
                        'challengeId': challenge.id,
                        'code': code.text,
                        if (challenge.isReset) 'password': password.text,
                      },
                    );
                    if (!context.mounted) return;
                    Navigator.of(context).popUntil((r) => r.isFirst);
                    toast(
                      context,
                      challenge.isReset
                          ? '비밀번호를 바꿨어요. 다시 로그인해 주세요.'
                          : '가입이 완료됐어요. 로그인해 주세요.',
                    );
                    openPage(
                      context,
                      LoginScreen(initialEmail: challenge.email),
                    );
                  });
                },
        ),
        OutlinedButton(
          onPressed: busy || cooldown > 0 || remaining <= 0
              ? null
              : () => run(() async {
                  final r = await RentalScope.of(context).api.request(
                    'POST',
                    '/api/auth/resend',
                    auth: Auth.none,
                    body: {'challengeId': challenge.id},
                  );
                  if (context.mounted) {
                    setState(() {
                      challenge = EmailChallenge.fromJson(
                        r,
                        email: challenge.email,
                        isReset: challenge.isReset,
                      );
                      code.clear();
                    });
                  }
                }),
          child: Text(
            cooldown > 0 ? '다시 받기 · ${cooldown + 1}초 후' : '확인번호 다시 받기',
          ),
        ),
        TextButton(
          onPressed: busy
              ? null
              : () => Navigator.of(context).popUntil((r) => r.isFirst),
          child: const Text('처음으로 돌아가기'),
        ),
      ],
    );
  }
}
