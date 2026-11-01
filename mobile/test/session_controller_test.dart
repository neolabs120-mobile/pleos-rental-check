import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/models/rental_models.dart';
import 'package:rental_check/state/rental_controller.dart';
import 'test_support.dart';

void main() {
  test(
    'login persists the scoped session and preserves a pending pairing request',
    () async {
      final store = MemoryStore();
      final controller = RentalController(
        store: store,
        api: RentalApi(
          'https://rental.example.com',
          client: MockClient(
            (_) async => jsonResponse({
              'token': 'account-session',
              'user': userJson,
              'expiresAtMillis':
                  DateTime.now()
                      .add(const Duration(hours: 8))
                      .millisecondsSinceEpoch,
            }),
          ),
        ),
      );
      controller.acceptLink(
        'https://rental.example.com/account#pair=${'a' * 43}',
      );
      await controller.login('renter@example.com', 'test-password');
      expect(controller.user!.nickname, '이용자');
      expect(controller.pendingLink, isNotNull);
      final saved = jsonDecode(store.values['session']!);
      expect(saved['origin'], 'https://rental.example.com');
      expect(saved.containsKey('password'), false);
      controller.dispose();
    },
  );
  test(
    'an expired persisted login does not make any authenticated request',
    () async {
      final store =
          MemoryStore()
            ..values['session'] = jsonEncode({
              'origin': 'https://rental.example.com',
              'token': 'expired',
              'expiresAtMillis': 1,
            });
      final controller = RentalController(
        store: store,
        api: RentalApi(
          'https://rental.example.com',
          client: MockClient((_) async {
            fail('expired token must not be sent');
          }),
        ),
      );
      await controller.restore();
      expect(controller.user, isNull);
      expect(store.values.containsKey('session'), false);
      controller.dispose();
    },
  );
  test('changing server drops old credentials and pending QR', () async {
    final store = MemoryStore()..values['session'] = 'old';
    final controller = RentalController(
      store: store,
      api: RentalApi('https://old.example.com'),
    );
    controller.api.accountToken = 'old-session';
    controller.user = AccountUser.fromJson(userJson);
    controller.acceptLink('https://old.example.com/account#pair=${'a' * 43}');
    await controller.configureServer('https://new.example.com');
    expect(controller.api.accountToken, isNull);
    expect(controller.pendingLink, isNull);
    expect(controller.user, isNull);
    expect(store.values['session'], isNull);
    expect(controller.api.base.host, 'new.example.com');
    controller.dispose();
  });
  test('a token saved for another origin is never restored', () async {
    final store =
        MemoryStore()
          ..values['session'] = jsonEncode({
            'origin': 'https://other.example.com',
            'token': 'other-session',
            'expiresAtMillis': 8640000000000000,
          });
    final controller = RentalController(
      store: store,
      api: RentalApi(
        'https://rental.example.com',
        client: MockClient((_) async {
          fail('cross-origin credential leak');
        }),
      ),
    );
    await controller.restore();
    expect(controller.api.accountToken, isNull);
    controller.dispose();
  });
}
