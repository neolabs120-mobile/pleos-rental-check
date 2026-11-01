import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/main.dart';
import 'package:rental_check/models/rental_models.dart';
import 'package:rental_check/state/rental_controller.dart';

import 'test_support.dart';

const point = {
  'name': '시험 반납 장소',
  'address': '시험 주소',
  'latitude': 37.48544722,
  'longitude': 127.03636666,
  'isTest': true,
};

// Verify optional booking metadata and the phone handoff without granting phone GPS access or claiming arrival.
void main() {
  test(
    'legacy destinations stay absent and invalid coordinates are rejected',
    () {
      // A missing or malformed server destination must never be replaced with a default real-world return location.
      expect(Booking.fromJson(bookingJson).returnLocation, isNull);
      expect(ReturnLocation.fromJson(point).isTest, isTrue);
      for (final change in [
        {'latitude': 91},
        {'longitude': double.nan},
        {'address': ' '},
      ]) {
        expect(
          () => ReturnLocation.fromJson({...point, ...change}),
          throwsFormatException,
        );
      }
    },
  );

  testWidgets(
    'booking shows a labelled test destination and preserves top QR access',
    (tester) async {
      // The phone displays operator-issued directions metadata while the vehicle remains responsible for the Pleos Navigation request.
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final controller =
          RentalController(
              store: MemoryStore(),
              api: RentalApi(
                'https://rental.example.com',
                client: MockClient((r) async {
                  expect(r.method, 'GET');
                  expect(r.url.path, '/api/account/bookings');
                  return jsonResponse({
                    'bookings': [
                      {...bookingJson, 'returnLocation': point},
                    ],
                  });
                }),
              ),
            )
            ..restoring = false
            ..user = AccountUser.fromJson(userJson)
            ..api.accountToken = 'mock-account';
      addTearDown(controller.dispose);
      await tester.pumpWidget(RentalCheckApp(controller: controller));
      await tester.pumpAndSettle();
      expect(find.text('차량 QR 스캔').hitTestable(), findsOneWidget);
      await tester.ensureVisible(find.text('반납 장소 · 시험 반납 장소'));
      await tester.pumpAndSettle();
      expect(find.text('시험 주소'), findsOneWidget);
      expect(find.text('에뮬레이터 시험 장소 · 실제 반납 거점이 아니에요.'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
