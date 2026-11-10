import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rental_check/models/rental_models.dart';
import 'package:rental_check/screens/receipt_screen.dart';
import 'package:rental_check/ui/trip_summary_card.dart';

// A returned record as the account endpoint summarizes it; the EV case has no fuel values.
RentalRecord record({
  bool ev = true,
  bool returned = true,
  double? distance = 186.5,
}) => RentalRecord.fromJson({
  'id': 'record-1',
  'revision': 2,
  'vinSuffix': 'ST0001',
  'photos': [
    for (final c in ['windows', 'belongings', 'exterior'])
      {'id': 'p-$c', 'phase': 'return', 'category': c},
  ],
  'checks': <String, dynamic>{},
  'pickupAtMillis': DateTime(2026, 11, 2, 9, 10).millisecondsSinceEpoch,
  if (returned)
    'returnAtMillis': DateTime(2026, 11, 3, 12, 40).millisecondsSinceEpoch,
  'distanceKm': distance,
  if (!ev) 'pickupFuel': 90.0,
  if (!ev) 'returnFuel': 95.0,
  'batteryReturn': ev
      ? {'targetPercent': 80.0, 'returnPercent': returned ? 72.0 : null}
      : <String, dynamic>{},
});

Future<void> show(WidgetTester tester, Widget child) => tester.pumpWidget(
  MaterialApp(
    home: Scaffold(body: SingleChildScrollView(child: child)),
  ),
);

void main() {
  test('duration is counted in whole days, hours and minutes', () {
    final start = DateTime(2026, 11, 2, 9, 10);
    expect(
      TripSummaryCard.durationText(start, DateTime(2026, 11, 3, 12, 40)),
      '1일 3시간 30분',
    );
    expect(
      TripSummaryCard.durationText(start, DateTime(2026, 11, 2, 11, 10)),
      '2시간',
    );
    expect(TripSummaryCard.durationText(start, start), '0분');
    expect(TripSummaryCard.durationText(start, null), isNull);
    expect(TripSummaryCard.durationText(start, DateTime(2026, 11, 1)), isNull);
  });

  testWidgets('EV summary shows time, distance and battery used', (
    tester,
  ) async {
    await show(tester, TripSummaryCard(record()));
    expect(find.text('1일 3시간 30분'), findsOneWidget);
    expect(find.text('186.5 km'), findsOneWidget);
    expect(find.text('80 → 72%'), findsOneWidget);
    expect(find.text('8%p 사용'), findsOneWidget);
    expect(find.text('사진 3장'), findsOneWidget);
    expect(find.text('연료'), findsNothing);
  });

  testWidgets('fuel vehicle uses fuel and missing distance is not invented', (
    tester,
  ) async {
    await show(tester, TripSummaryCard(record(ev: false, distance: null)));
    expect(find.text('연료'), findsOneWidget);
    expect(find.text('90 → 95%'), findsOneWidget);
    expect(find.text('5%p 늘어남'), findsOneWidget);
    expect(find.text('미수신'), findsOneWidget);
  });

  testWidgets('receipt shows the summary only for a returned record', (
    tester,
  ) async {
    final receipt = {'receiptId': 'R-1', 'submittedAtMillis': 1793510000000};
    await tester.pumpWidget(
      MaterialApp(
        home: ReceiptScreen(receipt: receipt, record: record()),
      ),
    );
    expect(find.text('이번 이용 요약'), findsOneWidget);
    await tester.pumpWidget(
      MaterialApp(
        home: ReceiptScreen(receipt: receipt, record: record(returned: false)),
      ),
    );
    expect(find.text('이번 이용 요약'), findsNothing);
    await tester.pumpWidget(MaterialApp(home: ReceiptScreen(receipt: receipt)));
    expect(find.text('이번 이용 요약'), findsNothing);
  });
}
