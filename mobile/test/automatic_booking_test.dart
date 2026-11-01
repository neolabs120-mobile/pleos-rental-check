import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/main.dart';
import 'package:rental_check/models/rental_models.dart';
import 'package:rental_check/state/rental_controller.dart';

import 'test_support.dart';

// Exercise a reservation issued after home opens, ensuring one QR refreshes it and still requires the driver's explicit confirmation.
void main() {
  testWidgets('scanning discovers a later reservation without manual claim', (
    tester,
  ) async {
    // The app must refresh after reading the QR, keep number/code fields out of the normal flow, and approve only after the confirmation dialog.
    int reads = 0, approvals = 0;
    final state = RentalController(
      store: MemoryStore(),
      api: RentalApi(
        'https://rental.example.com',
        client: MockClient((request) async {
          if (request.url.path == '/api/account/bookings') {
            return jsonResponse({
              'bookings': ++reads == 1 ? [] : [bookingJson],
            });
          }
          if (request.url.path == '/api/account/pairings/approve') {
            approvals++;
            expect(jsonDecode(request.body)['bookingId'], bookingJson['id']);
            return jsonResponse({'connected': true});
          }
          fail(
            'Manual claim and other unexpected requests are not part of the QR flow: ${request.url.path}',
          );
        }),
      ),
    )..restoring = false;
    state.user = AccountUser.fromJson(userJson);
    state.api.accountToken = 'mock-account';
    await tester.pumpWidget(RentalCheckApp(controller: state));
    await tester.pumpAndSettle();
    expect(find.text('이용할 예약이 없어요'), findsOneWidget);
    expect(find.byType(TextFormField), findsNothing);
    await tester.ensureVisible(find.text('차량 QR 스캔'));
    await tester.tap(find.text('차량 QR 스캔'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byType(TextField),
      'https://rental.example.com/account#pair=${'a' * 43}',
    );
    await tester.ensureVisible(find.text('링크 확인'));
    await tester.tap(find.text('링크 확인'));
    await tester.pumpAndSettle();
    expect(reads, 2);
    expect(find.text('시험 차량'), findsOneWidget);
    expect(approvals, 0);
    await tester.ensureVisible(find.widgetWithText(FilledButton, '이 차량 연결 승인'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, '이 차량 연결 승인'));
    await tester.pumpAndSettle();
    expect(find.text('이 차량에 연결할까요?'), findsOneWidget);
    expect(approvals, 0);
    await tester.tap(find.text('연결 승인'));
    await tester.pumpAndSettle();
    expect(approvals, 1);
    expect(state.pendingLink, isNull);
    await tester.pumpWidget(const SizedBox());
    state.dispose();
  });
}
