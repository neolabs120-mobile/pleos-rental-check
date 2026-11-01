import 'package:flutter/material.dart';
import '../app_scope.dart';
import '../ui/components.dart';

class ClaimBookingScreen extends StatefulWidget {
  const ClaimBookingScreen({super.key});
  @override
  State<ClaimBookingScreen> createState() => _ClaimBookingScreenState();
}

class _ClaimBookingScreenState extends State<ClaimBookingScreen>
    with ActionState {
  final form = GlobalKey<FormState>();
  final number = TextEditingController(), code = TextEditingController();
  @override
  void dispose() {
    number.dispose();
    code.dispose();
    super.dispose();
  }

  @override
  // Retain issued booking codes as a fallback while explaining that normal reservations appear automatically for the same verified email.
  Widget build(BuildContext context) => AppPage(
    title: '예약 직접 연결',
    children: [
      const Intro(
        '예약이 보이지 않을 때만 입력해요',
        '예약은 로그인한 이메일로 자동 표시돼요. 별도로 받은 예약번호와 연결 코드가 있다면 여기서 연결할 수 있어요.',
      ),
      Notice(
        '현재 계정: ${RentalScope.of(context).user?.email ?? ''}\n예약을 받은 이메일과 로그인한 이메일이 같아야 해요.',
      ),
      Form(
        key: form,
        child: Column(
          children: [
            TextFormField(
              controller: number,
              maxLength: 40,
              decoration: const InputDecoration(
                labelText: '예약번호',
                hintText: 'RC-…',
              ),
              validator: (v) =>
                  v == null || v.trim().isEmpty ? '예약번호를 입력해 주세요.' : null,
            ),
            const SizedBox(height: 20),
            TextFormField(
              controller: code,
              maxLength: 128,
              autocorrect: false,
              enableSuggestions: false,
              decoration: const InputDecoration(labelText: '연결 코드'),
              validator: (v) => v == null || v.trim().length < 16
                  ? '발급받은 연결 코드를 확인해 주세요.'
                  : null,
            ),
          ],
        ),
      ),
      if (error != null) Notice(error!, isError: true),
      PrimaryAction(
        '내 대여에 연결',
        busy: busy,
        onPressed: () {
          if (!form.currentState!.validate()) return;
          run(() async {
            final state = RentalScope.of(context);
            await state.api.claim(number.text, code.text);
            await state.refreshBookings();
            if (context.mounted) {
              Navigator.of(context).pop();
              toast(context, '대여 건을 연결했어요.');
            }
          });
        },
      ),
    ],
  );
}
