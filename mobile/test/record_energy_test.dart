import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:rental_check/app_scope.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/models/rental_models.dart';
import 'package:rental_check/screens/receipt_screen.dart';
import 'package:rental_check/screens/record_screen.dart';
import 'package:rental_check/state/rental_controller.dart';

import 'test_support.dart';

// An electric rental record as the server summarizes it: no fuel, battery reference from the saved pickup.
Json evRecord({required bool returned}) => {
  'id': 'record-ev',
  'revision': returned ? 2 : 1,
  'vinSuffix': 'ST0001',
  'isDemo': true,
  'photos': <dynamic>[],
  'checks': <String, dynamic>{},
  'pickupAtMillis': 1793500000000,
  if (returned) 'returnAtMillis': 1793510000000,
  if (returned) 'distanceKm': 186.5,
  'batteryReturn': {
    'targetPercent': 80.0,
    'returnPercent': returned ? 72.0 : null,
    'shortfallPercent': returned ? 8.0 : null,
  },
};

Future<void> showRecord(WidgetTester tester, Json record) async {
  final controller = RentalController(
    store: MemoryStore(),
    api: RentalApi(
      'https://rental.example.com',
      client: MockClient((_) async => jsonResponse(record)),
    )..accountToken = 'mock-account',
  )..user = AccountUser.fromJson(userJson);
  addTearDown(controller.dispose);
  final booking = Booking.fromJson({...bookingJson, 'rentalId': record['id']});
  await tester.pumpWidget(
    RentalScope(
      controller: controller,
      child: MaterialApp(home: RecordScreen(booking: booking)),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  // Before the return is saved, distance is not "not received" but not yet computable, and an EV shows battery rather than fuel.
  testWidgets(
    'pickup-only EV record says distance comes after return and shows battery',
    (tester) async {
      await showRecord(tester, evRecord(returned: false));
      expect(find.text('반납 후 계산'), findsOneWidget);
      expect(find.text('배터리 · 인수 때'), findsOneWidget);
      expect(find.text('80.0%'), findsOneWidget);
      expect(find.text('연료 잔량'), findsNothing);
      expect(find.text('미수신'), findsNothing);
    },
  );

  testWidgets(
    'returned EV record shows distance and the saved battery change',
    (tester) async {
      await showRecord(tester, evRecord(returned: true));
      expect(find.text('186.5 km'), findsOneWidget);
      expect(find.text('80.0% → 72.0%'), findsOneWidget);
    },
  );

  // A synthetic demo submission keeps its label on the final receipt and uses the car-sharing wording.
  testWidgets('demo receipt is labelled as synthetic data', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: ReceiptScreen(
          receipt: {
            'receiptId': 'receipt-1',
            'submittedAtMillis': 1793510000000,
          },
          isDemo: true,
        ),
      ),
    );
    expect(find.textContaining('합성 시연 데이터로 진행한 시험 접수'), findsOneWidget);
    expect(find.textContaining('카셰어링 업체'), findsOneWidget);
    expect(find.textContaining('렌터카'), findsNothing);
  });
}
