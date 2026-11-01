import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/models/rental_models.dart';
import 'test_support.dart';

void main() {
  test('photo calls use the photo grant AND account token', () async {
    final api = RentalApi(
      'https://rental.example.com',
      client: MockClient((r) async {
        expect(r.headers['authorization'], 'Bearer photo-grant');
        expect(r.headers['x-account-token'], 'account-session');
        expect(r.followRedirects, false);
        return jsonResponse({'ok': true});
      }),
    )..accountToken = 'account-session';
    await api.saveChecks('photo-grant', const ManualChecks(windows: true));
    api.close();
  });
  test('login never sends an old session token', () async {
    final api = RentalApi(
      'https://rental.example.com',
      client: MockClient((r) async {
        expect(r.headers.containsKey('authorization'), false);
        expect(jsonDecode(r.body)['email'], 'renter@example.com');
        return jsonResponse({});
      }),
    )..accountToken = 'old-session';
    await api.login(' renter@example.com ', 'password');
    api.close();
  });
  test(
    'account expiration callback is separate from an expired photo grant',
    () async {
      int expired = 0;
      final api = RentalApi(
        'https://rental.example.com',
        client: MockClient((_) async => jsonResponse({'detail': '만료됐어요'}, 401)),
      )..accountToken = 'session';
      api.onAccountExpired = () => expired++;
      await expectLater(
        api.saveChecks('expired-photo', const ManualChecks()),
        throwsA(isA<ApiException>()),
      );
      expect(expired, 0);
      await expectLater(api.me(), throwsA(isA<ApiException>()));
      expect(expired, 1);
      api.close();
    },
  );
  test('server rejection detail remains visible to the user', () async {
    final api = RentalApi(
      'https://rental.example.com',
      client: MockClient(
        (_) async => jsonResponse({'detail': '같은 차량의 기록이 필요해요.'}, 409),
      ),
    )..accountToken = 'session';
    await expectLater(
      api.submit('photo', 2),
      throwsA(
        isA<ApiException>()
            .having((e) => e.status, 'status', 409)
            .having((e) => e.message, 'message', '같은 차량의 기록이 필요해요.'),
      ),
    );
    api.close();
  });
  test(
    'upload retry retains request ID and encodes the original bytes',
    () async {
      final ids = <String>[];
      final api = RentalApi(
        'https://rental.example.com',
        client: MockClient((r) async {
          final payload = jsonDecode(r.body);
          ids.add(payload['requestId'] as String);
          expect(base64Decode(payload['imageBase64'] as String), [
            255,
            216,
            255,
          ]);
          return jsonResponse({});
        }),
      )..accountToken = 'session';
      for (int i = 0; i < 2; i++) {
        await api.upload(
          'photo',
          EvidenceCategory.windows,
          'stable-id',
          Uint8List.fromList([255, 216, 255]),
        );
      }
      expect(ids, ['stable-id', 'stable-id']);
      api.close();
    },
  );
  test('upload cannot proceed without an account session', () async {
    final api = RentalApi(
      'https://rental.example.com',
      client: MockClient((_) async {
        fail('unauthenticated requests must not leave the client');
      }),
    );
    await expectLater(
      api.saveChecks('photo', const ManualChecks()),
      throwsA(isA<ApiException>()),
    );
    api.close();
  });
  test(
    'submission requires saved checks, all categories and an unexpired return grant',
    () {
      Map<String, dynamic> fixture({
        String phase = 'return',
        int expires = 8640000000000000,
      }) => {
        'id': 'rental',
        'revision': 2,
        'vinSuffix': 'ST0001',
        'isDemo': true,
        'receiptId': null,
        'submittedAtMillis': null,
        'photos': [
          for (final c in EvidenceCategory.values)
            {'id': c.name, 'phase': phase, 'category': c.name},
        ],
        'checks': {
          'windows': true,
          'belongings': true,
          'exterior': true,
          'note': '',
        },
        'phase': phase,
        'expiresAtMillis': expires,
      };
      expect(UploadSession.fromJson(fixture()).ready, true);
      expect(UploadSession.fromJson(fixture(phase: 'pickup')).ready, false);
      expect(UploadSession.fromJson(fixture(expires: 1)).ready, false);
      expect(UploadSession.fromJson({...fixture(), 'photos': []}).ready, false);
      expect(
        UploadSession.fromJson({
          ...fixture(),
          'checks': {'windows': true},
        }).ready,
        false,
      );
    },
  );
}
