import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:rental_check/data/connection_link.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/models/rental_models.dart';

void main() {
  test(
    'Flutter API client against a real isolated FastAPI server',
    () async {
      final fixture =
          jsonDecode(
                await File(
                  Platform.environment['RENTAL_TEST_FIXTURE']!,
                ).readAsString(),
              )
              as Json;
      final api = RentalApi(fixture['baseUrl'] as String);
      addTearDown(api.close);
      final email = fixture['email'] as String,
          password = fixture['password'] as String;
      expect(
        (await api.request('GET', '/health', auth: Auth.none))['status'],
        'ok',
      );
      final challenge = await api.signup('Flutter 시험 이용자', email, password);
      expect(challenge['deliveryMode'], 'file');
      await expectLater(
        api.login(email, password),
        throwsA(isA<ApiException>().having((e) => e.status, 'status', 401)),
      );
      final files =
          Directory(
            '${fixture['dataDir']}/mail-outbox',
          ).listSync().whereType<File>();
      final message = files
          .map((f) => jsonDecode(f.readAsStringSync()) as Json)
          .singleWhere((m) => m['email'] == email && m['purpose'] == 'signup');
      await api.request(
        'POST',
        '/api/auth/verify',
        auth: Auth.none,
        body: {
          'challengeId': challenge['challengeId'],
          'code': message['code'],
        },
      );
      final login = await api.login(email, password);
      api.accountToken = login['token'] as String;
      expect((await api.me()).email, email);
      await expectLater(
        api.claim(fixture['bookingNumber'] as String, 'incorrect-claim-code'),
        throwsA(isA<ApiException>().having((e) => e.status, 'status', 403)),
      );
      await api.claim(
        fixture['bookingNumber'] as String,
        fixture['claimCode'] as String,
      );
      final bookings = await api.bookings();
      expect(bookings.length, 1);
      expect(bookings.single.isDemo, true);
      final pair = ConnectionLink.parse(
        fixture['pairingUrl'] as String,
        api.base,
      );
      await api.approve(pair.token, bookings.single.id);
      final record = await api.record(fixture['rentalId'] as String);
      expect(record.isDemo, true);
      expect(record.distanceKm, 186.5);
      expect(record.pickupFuel, 80);
      expect(record.returnFuel, 58);
      final link = await api.photoLink(record.id, 'return');
      final grant =
          ConnectionLink.parse(link['uploadUrl'] as String, api.base).token;
      expect((await api.uploadSession(grant)).ready, false);
      await expectLater(
        api.submit(grant, record.revision),
        throwsA(isA<ApiException>().having((e) => e.status, 'status', 409)),
      );
      final image = base64Decode(fixture['imageBase64'] as String);
      final ids = fixture['requestIds'] as List;
      for (final c in EvidenceCategory.values) {
        await api.upload(grant, c, ids[c.index] as String, image);
      }
      // Simulate a response-lost retry: one request ID must still yield one photo.
      await api.upload(
        grant,
        EvidenceCategory.windows,
        ids[0] as String,
        image,
      );
      final uploaded = await api.uploadSession(grant);
      expect(uploaded.record.photos.length, 3);
      await api.deletePhoto(
        grant,
        uploaded.photosFor(EvidenceCategory.windows).single.id,
      );
      expect((await api.uploadSession(grant)).record.photos.length, 2);
      await api.upload(
        grant,
        EvidenceCategory.windows,
        fixture['replacementRequestId'] as String,
        image,
      );
      await api.saveChecks(
        grant,
        const ManualChecks(
          windows: true,
          belongings: true,
          exterior: true,
          note: 'Flutter HTTP 연동 시험',
        ),
      );
      expect((await api.uploadSession(grant)).ready, true);
      final receipt = await api.submit(grant, record.revision);
      expect(receipt['receiptId'], isNotEmpty);
      expect(
        (await api.submit(grant, record.revision))['receiptId'],
        receipt['receiptId'],
      );
      final stored = await api.record(record.id);
      expect(stored.isSubmitted, true);
      expect(stored.photos.length, 3);
      expect(await api.photo(stored.photos.first.id), isNotEmpty);
      await api.logout();
      await expectLater(
        api.me(),
        throwsA(isA<ApiException>().having((e) => e.status, 'status', 401)),
      );
    },
    skip: !const bool.fromEnvironment('RENTAL_LIVE_TEST'),
    timeout: const Timeout(Duration(minutes: 2)),
  );
}
