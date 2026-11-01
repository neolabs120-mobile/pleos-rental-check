import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/main.dart';
import 'package:rental_check/state/rental_controller.dart';
import 'test_support.dart';

void main() {
  testWidgets(
    'welcome opens the native signup form and validates required inputs',
    (tester) async {
      final controller = RentalController(
        store: MemoryStore(),
        api: RentalApi(
          'https://rental.example.com',
          client: MockClient((_) async {
            fail('invalid form must not call an API');
          }),
        ),
      )..restoring = false;
      await tester.pumpWidget(RentalCheckApp(controller: controller));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('계정 만들기'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('계정 만들기'));
      await tester.pumpAndSettle();
      expect(find.byType(TextFormField), findsNWidgets(4));
      await tester.ensureVisible(find.text('이메일 확인번호 받기'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('이메일 확인번호 받기'));
      await tester.pumpAndSettle();
      expect(find.text('닉네임을 입력해 주세요.'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );
  testWidgets(
    'login loads account-owned bookings without placeholder vehicle readings',
    (tester) async {
      final controller = RentalController(
        store: MemoryStore(),
        api: RentalApi(
          'https://rental.example.com',
          client: MockClient((r) async {
            if (r.url.path == '/api/auth/login') {
              return jsonResponse({
                'token': 'session',
                'user': userJson,
                'expiresAtMillis':
                    DateTime.now()
                        .add(const Duration(hours: 8))
                        .millisecondsSinceEpoch,
              });
            }
            if (r.url.path == '/api/account/bookings') {
              return jsonResponse({
                'bookings': [bookingJson],
              });
            }
            fail('unexpected route ${r.url.path}');
          }),
        ),
      )..restoring = false;
      await tester.pumpWidget(RentalCheckApp(controller: controller));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('이미 계정이 있어요 · 로그인'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('이미 계정이 있어요 · 로그인'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byType(TextFormField).at(0),
        'renter@example.com',
      );
      await tester.enterText(find.byType(TextFormField).at(1), 'test-password');
      await tester.tap(find.widgetWithText(FilledButton, '로그인'));
      await tester.pumpAndSettle();
      expect(find.text('시험 차량'), findsOneWidget);
      expect(find.text('VIN 끝 6자리 ST0001'), findsOneWidget);
      expect(find.text('합성 시연 데이터'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );
  testWidgets('welcome remains usable on a narrow screen with large text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final controller = RentalController(
      store: MemoryStore(),
      api: RentalApi('https://rental.example.com'),
    )..restoring = false;
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.pumpWidget(RentalCheckApp(controller: controller));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });
}
