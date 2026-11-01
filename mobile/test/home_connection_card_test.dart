import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/main.dart';
import 'package:rental_check/models/rental_models.dart';
import 'package:rental_check/state/rental_controller.dart';

import 'test_support.dart';

// Supply account-owned reservations through the normal API so layout tests also detect accidental writes or manual-claim requests.
RentalController controllerFor(List<Json> bookings) =>
    RentalController(
        store: MemoryStore(),
        api: RentalApi(
          'https://rental.example.com',
          client: MockClient((request) async {
            expect(request.method, 'GET');
            expect(request.url.path, '/api/account/bookings');
            return jsonResponse({'bookings': bookings});
          }),
        ),
      )
      ..restoring = false
      ..user = AccountUser.fromJson(userJson)
      ..api.accountToken = 'mock-account';

// Cover the regression that completed rentals buried QR access, including narrow screens and the camera-free validation path.
void main() {
  for (final width in [320.0, 360.0, 840.0]) {
    testWidgets('QR and link stay visible above accumulated history at $width', (
      tester,
    ) async {
      // Increasing completed history must not move connection actions below the initial viewport or remove access to old receipts.
      tester.view.physicalSize = Size(width, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final bookings = [
        bookingJson,
        for (var i = 0; i < 12; i++)
          {
            ...bookingJson,
            'id': 'past-$i',
            'vehicleName': '완료 차량 $i',
            'rentalId': 'record-$i',
            'submittedAtMillis': 1700000000000,
          },
      ];
      final controller = controllerFor(bookings);
      addTearDown(controller.dispose);
      await tester.pumpWidget(RentalCheckApp(controller: controller));
      await tester.pumpAndSettle();
      final navigationTop = tester.getTopLeft(find.byType(NavigationBar)).dy;
      for (final label in ['차량 QR 스캔', '카메라가 없나요? 링크 입력']) {
        expect(find.text(label).hitTestable(), findsOneWidget);
        expect(
          tester.getBottomLeft(find.text(label)).dy,
          lessThan(navigationTop),
        );
      }
      expect(find.text('1건'), findsOneWidget);
      expect(find.text('완료 차량 0'), findsNothing);
      await tester.tap(find.text('기록'));
      await tester.pumpAndSettle();
      expect(find.text('완료 차량 0'), findsOneWidget);
      expect(find.text('접수 결과·기록 보기'), findsNWidgets(12));
      expect(controller.bookings.length, 13);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }

  testWidgets(
    'direct link entry needs no camera and does not approve a vehicle',
    (tester) async {
      // Reject a foreign server and leave a valid QR pending until the driver separately confirms the selected vehicle.
      final controller = controllerFor([bookingJson]);
      addTearDown(controller.dispose);
      await tester.pumpWidget(RentalCheckApp(controller: controller));
      await tester.pumpAndSettle();
      await tester.tap(find.text('카메라가 없나요? 링크 입력'));
      await tester.pumpAndSettle();
      expect(find.text('QR 링크 입력'), findsOneWidget);
      expect(find.byType(MobileScanner), findsNothing);
      await tester.enterText(
        find.byType(TextField),
        'https://other.example.com/account#pair=${'a' * 43}',
      );
      await tester.ensureVisible(find.text('링크 확인'));
      await tester.tap(find.text('링크 확인'));
      await tester.pumpAndSettle();
      expect(controller.pendingLink, isNull);
      expect(find.byType(TextField), findsOneWidget);
      await tester.enterText(
        find.byType(TextField),
        'https://rental.example.com/account#pair=${'a' * 43}',
      );
      await tester.ensureVisible(find.text('링크 확인'));
      await tester.tap(find.text('링크 확인'));
      await tester.pumpAndSettle();
      expect(controller.pendingLink, isNotNull);
      expect(find.text('이 차량 연결 승인'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('empty active list retains history and supports larger text', (
    tester,
  ) async {
    // A driver with only completed rentals should still find both connection methods and reach their receipt from the home history link.
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final controller = controllerFor([
      {
        ...bookingJson,
        'rentalId': 'past-record',
        'submittedAtMillis': 1700000000000,
      },
    ]);
    addTearDown(controller.dispose);
    await tester.pumpWidget(RentalCheckApp(controller: controller));
    await tester.pumpAndSettle();
    expect(find.text('이용할 예약이 없어요'), findsOneWidget);
    expect(find.text('차량 QR 스캔'), findsOneWidget);
    await tester.ensureVisible(find.text('지난 이용 기록 보기'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('지난 이용 기록 보기'));
    await tester.pumpAndSettle();
    expect(find.text('접수 결과·기록 보기'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });
}
