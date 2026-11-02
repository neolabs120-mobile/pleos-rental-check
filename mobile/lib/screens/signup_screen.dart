import 'package:flutter/material.dart';
import '../app_scope.dart';
import '../models/rental_models.dart';
import '../ui/components.dart';
import 'policy_screen.dart';
import 'verification_screen.dart';

class SignupScreen extends StatefulWidget {
  const SignupScreen({super.key});
  @override
  State<SignupScreen> createState() => _SignupScreenState();
}

class _SignupScreenState extends State<SignupScreen> with ActionState {
  final form = GlobalKey<FormState>();
  final name = TextEditingController(), email = TextEditingController();
  final password = TextEditingController(), confirm = TextEditingController();
  bool terms = false, privacy = false;
  @override
  void dispose() {
    for (final c in [name, email, password, confirm]) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  // Set the expectation that verified login discovers operator-issued reservations without a separate number/code entry step.
  Widget build(BuildContext context) => AppPage(
    title: '계정 만들기',
    children: [
      const Intro('나의 렌트체크 시작하기', '예약에 사용한 이메일을 확인하면 로그인 후 내 예약이 자동으로 보여요.'),
      Form(
        key: form,
        child: AutofillGroup(
          child: Column(
            children: [
              TextFormField(
                controller: name,
                maxLength: 32,
                autofillHints: const [AutofillHints.nickname],
                decoration: const InputDecoration(
                  labelText: '닉네임',
                  counterText: '',
                ),
                validator: (v) =>
                    v == null || v.trim().isEmpty ? '닉네임을 입력해 주세요.' : null,
              ),
              const SizedBox(height: 20),
              emailField(email),
              const SizedBox(height: 20),
              PasswordField(controller: password, newPassword: true),
              const SizedBox(height: 20),
              PasswordField(
                controller: confirm,
                label: '비밀번호 확인',
                newPassword: true,
                confirm: password,
              ),
            ],
          ),
        ),
      ),
      Panel(
        child: Column(
          children: [
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              value: terms,
              controlAffinity: ListTileControlAffinity.leading,
              title: const Text('이용약관 동의 (필수)'),
              onChanged: busy ? null : (v) => setState(() => terms = v!),
            ),
            TextButton(
              onPressed: () =>
                  openPage(context, const PolicyScreen(privacy: false)),
              child: const Text('이용약관 보기'),
            ),
            CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              value: privacy,
              controlAffinity: ListTileControlAffinity.leading,
              title: const Text('개인정보 수집·이용 동의 (필수)'),
              onChanged: busy ? null : (v) => setState(() => privacy = v!),
            ),
            TextButton(
              onPressed: () =>
                  openPage(context, const PolicyScreen(privacy: true)),
              child: const Text('개인정보 안내 보기'),
            ),
          ],
        ),
      ),
      if (error != null) Notice(error!, isError: true),
      PrimaryAction(
        '이메일 확인번호 받기',
        busy: busy,
        onPressed: () {
          if (!form.currentState!.validate()) return;
          if (!terms || !privacy) {
            setState(() => error = '필수 약관 두 항목에 동의해 주세요.');
            return;
          }
          run(() async {
            final result = await RentalScope.of(
              context,
            ).api.signup(name.text, email.text, password.text);
            if (!context.mounted) return;
            password.clear();
            confirm.clear();
            Navigator.of(context).pushReplacement(
              MaterialPageRoute(
                builder: (_) => VerificationScreen(
                  challenge: EmailChallenge.fromJson(
                    result,
                    email: email.text.trim(),
                    isReset: false,
                  ),
                ),
              ),
            );
          });
        },
      ),
    ],
  );
}
