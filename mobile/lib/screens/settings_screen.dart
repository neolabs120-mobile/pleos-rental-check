import 'package:flutter/material.dart';
import '../app_scope.dart';
import '../ui/components.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> with ActionState {
  final address = TextEditingController();
  bool initialized = false;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!initialized) {
      address.text = RentalScope.of(context).api.base.toString();
      initialized = true;
    }
  }

  @override
  void dispose() {
    address.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '연결 설정',
    children: [
      const Intro('서버 연결', '휴대폰에서 접속할 수 있는 렌트체크 서버 주소를 입력해 주세요.'),
      TextField(
        controller: address,
        keyboardType: TextInputType.url,
        autocorrect: false,
        decoration: const InputDecoration(
          labelText: '서버 주소',
          hintText: 'https://rental.example.com',
        ),
      ),
      const Notice(
        '실제 휴대폰은 맥과 같은 Wi-Fi에서 맥의 LAN 주소로 접속해요. 서버를 바꾸면 이 기기의 로그인 정보가 초기화돼요.',
      ),
      if (error != null) Notice(error!, isError: true),
      PrimaryAction(
        '주소 저장',
        busy: busy,
        onPressed:
            () => run(() async {
              await RentalScope.of(context).configureServer(address.text);
              if (context.mounted) {
                Navigator.of(context).popUntil((r) => r.isFirst);
                toast(context, '서버 주소를 저장했어요.');
              }
            }),
      ),
    ],
  );
}
