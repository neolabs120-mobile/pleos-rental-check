import 'package:flutter/material.dart';
import '../app_scope.dart';
import '../ui/components.dart';
import 'reset_password_screen.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, this.initialEmail = ''});
  final String initialEmail;
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> with ActionState {
  final form = GlobalKey<FormState>();
  late final email = TextEditingController(text: widget.initialEmail);
  final password = TextEditingController();
  @override
  void dispose() {
    email.dispose();
    password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '로그인',
    children: [
      const Intro('다시 만나서 반가워요', '내 대여와 점검 기록을 이어서 확인해요.'),
      Form(
        key: form,
        child: AutofillGroup(
          child: Column(
            children: [
              emailField(email),
              const SizedBox(height: 20),
              PasswordField(controller: password),
            ],
          ),
        ),
      ),
      if (error != null) Notice(error!, isError: true),
      PrimaryAction(
        '로그인',
        busy: busy,
        onPressed: () {
          if (form.currentState!.validate()) {
            run(() async {
              await RentalScope.of(context).login(email.text, password.text);
              if (context.mounted) {
                Navigator.of(context).popUntil((r) => r.isFirst);
              }
            });
          }
        },
      ),
      TextButton(
        onPressed: busy
            ? null
            : () => openPage(context, const ResetPasswordScreen()),
        child: const Text('비밀번호를 잊으셨나요?'),
      ),
    ],
  );
}
