import 'package:flutter/material.dart';
import '../app_scope.dart';
import '../data/rental_api.dart';
import '../models/rental_models.dart';
import '../ui/components.dart';
import 'verification_screen.dart';

class ResetPasswordScreen extends StatefulWidget {
  const ResetPasswordScreen({super.key});
  @override
  State<ResetPasswordScreen> createState() => _ResetPasswordScreenState();
}

class _ResetPasswordScreenState extends State<ResetPasswordScreen>
    with ActionState {
  final form = GlobalKey<FormState>();
  final email = TextEditingController();
  @override
  void dispose() {
    email.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '비밀번호 재설정',
    children: [
      const Intro('비밀번호를 다시 설정해요', '가입한 이메일이면 확인번호를 보내요. 변경 후에는 다시 로그인해야 해요.'),
      Form(key: form, child: emailField(email)),
      if (error != null) Notice(error!, isError: true),
      PrimaryAction(
        '확인번호 받기',
        busy: busy,
        onPressed: () {
          if (!form.currentState!.validate()) return;
          run(() async {
            final r = await RentalScope.of(context).api.request(
              'POST',
              '/api/auth/password/request',
              auth: Auth.none,
              body: {'email': email.text.trim()},
            );
            if (!context.mounted) return;
            Navigator.of(context).pushReplacement(
              MaterialPageRoute(
                builder: (_) => VerificationScreen(
                  challenge: EmailChallenge.fromJson(
                    r,
                    email: email.text.trim(),
                    isReset: true,
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
