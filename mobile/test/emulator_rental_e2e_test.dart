import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:rental_check/data/connection_link.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/models/rental_models.dart';

// Poll the real server or the private host rendezvous file with a deadline; absent records and SDK signals never count as completion.
Future<T> eventually<T>(String label, Future<T?> Function() probe) async {
  final deadline = DateTime.now().add(const Duration(minutes: 3));
  while (DateTime.now().isBefore(deadline)) {
    final value = await probe();
    if (value != null) return value;
    await Future<void>.delayed(const Duration(milliseconds: 350));
  }
  throw StateError('Timed out waiting for $label');
}

// Write private browser/driver rendezvous atomically so neither process can read a partially written credential or receipt file.
Future<void> writePrivate(File target, Json value) async {
  final temporary = File('${target.path}.next');
  await temporary.writeAsString(jsonEncode(value));
  await temporary.rename(target.path);
}

// Exercise the production Flutter client against a real server and the genuine AVD controller, without manual booking claims or synthesized vehicle records.
void main() {
  test(
    'rental from verified account through AVD return and final receipt',
    () async {
      final fixtureFile = File(Platform.environment['RENTAL_E2E_FIXTURE']!);
      final fixture = jsonDecode(await fixtureFile.readAsString()) as Json;
      final api = RentalApi(fixture['baseUrl'] as String);
      addTearDown(api.close);
      final challenge = await api.signup(
        '자동 전체 흐름 시험',
        fixture['email'] as String,
        fixture['password'] as String,
      );
      await expectLater(
        api.login(fixture['email'] as String, fixture['password'] as String),
        throwsA(isA<ApiException>().having((e) => e.status, 'status', 401)),
      );
      final mail = await eventually<Json>('local verification mail', () async {
        for (final file in Directory(
          '${fixture['dataDir']}/mail-outbox',
        ).listSync().whereType<File>()) {
          final value = jsonDecode(await file.readAsString()) as Json;
          if (value['email'] == fixture['email'] &&
              value['purpose'] == 'signup') {
            return value;
          }
        }
        return null;
      });
      await api.request(
        'POST',
        '/api/auth/verify',
        auth: Auth.none,
        body: {'challengeId': challenge['challengeId'], 'code': mail['code']},
      );
      final login = await api.login(
        fixture['email'] as String,
        fixture['password'] as String,
      );
      api.accountToken = login['token'] as String;
      final rows = await api.bookings();
      expect(rows.single.id, fixture['bookingId']);
      final pairing = await eventually<String>(
        'vehicle QR',
        () async =>
            (jsonDecode(await fixtureFile.readAsString()) as Json)['pairingUrl']
                as String?,
      );
      await api.approve(
        ConnectionLink.parse(pairing, api.base).token,
        rows.single.id,
      );
      printOnFailure(
        'PHONE: verified login, automatic reservation and explicit vehicle approval passed',
      );
      final recordId = await eventually<String>(
        'real pickup saved by AVD',
        () async => (await api.bookings()).single.rentalId,
      );
      final returned = await eventually<RentalRecord>(
        'real return saved by AVD',
        () async {
          final value = await api.record(recordId);
          return value.returnAt == null ? null : value;
        },
      );
      expect(returned.usesTestVin, true);
      expect(returned.distanceKm, 3.0);
      expect(
        returned.usageMemory.opened.toSet(),
        containsAll(['REAR_RIGHT', 'TRUNK']),
      );
      expect(returned.usageMemory.marked, ['TRUNK']);
      final link = await api.photoLink(recordId, 'return');
      final grant = ConnectionLink.parse(
        link['uploadUrl'] as String,
        api.base,
      ).token;
      const complete = ManualChecks(
        windows: true,
        belongings: true,
        exterior: true,
        note: '자동 시험: 생성한 사진이며 실제 차량 사진이 아닙니다.',
      );
      await api.saveChecks(grant, complete);
      await expectLater(
        api.submit(grant, returned.revision),
        throwsA(
          isA<ApiException>().having((e) => e.status, 'missing photos', 409),
        ),
      );
      for (final category in EvidenceCategory.values) {
        final image = await File(
          (fixture['photos'] as Json)[category.name] as String,
        ).readAsBytes();
        await api.upload(
          grant,
          category,
          (fixture['requestIds'] as Json)[category.name] as String,
          image,
        );
        if (category != EvidenceCategory.exterior) {
          await expectLater(
            api.submit(grant, returned.revision),
            throwsA(
              isA<ApiException>().having(
                (e) => e.status,
                'missing photo category',
                409,
              ),
            ),
          );
        }
      }
      final first = EvidenceCategory.windows;
      await api.upload(
        grant,
        first,
        (fixture['requestIds'] as Json)[first.name] as String,
        await File(
          (fixture['photos'] as Json)[first.name] as String,
        ).readAsBytes(),
      );
      expect((await api.uploadSession(grant)).record.photos.length, 3);
      await api.saveChecks(
        grant,
        const ManualChecks(windows: true, belongings: false, exterior: true),
      );
      await expectLater(
        api.submit(grant, returned.revision),
        throwsA(
          isA<ApiException>().having(
            (e) => e.status,
            'missing manual check',
            409,
          ),
        ),
      );
      await api.saveChecks(grant, complete);
      expect((await api.uploadSession(grant)).ready, true);
      final receipt = await api.submit(grant, returned.revision);
      expect(
        (await api.submit(grant, returned.revision))['receiptId'],
        receipt['receiptId'],
      );
      final stored = await api.record(recordId);
      expect(stored.isSubmitted, true);
      expect(stored.photos.length, 3);
      expect(await api.photo(stored.photos.first.id), isNotEmpty);
      await writePrivate(File('${fixture['workDir']}/phone-result.json'), {
        'recordId': recordId,
        'receiptId': receipt['receiptId'],
        'distanceKm': stored.distanceKm,
        'checks': {
          'verifiedEmail': true,
          'automaticReservation': true,
          'noManualClaim': true,
          'realAvdRecord': true,
          'missingPhotosBlocked': true,
          'missingCategoryBlocked': true,
          'missingManualCheckBlocked': true,
          'uploadRetryDeduplicated': true,
          'receiptRetryIdempotent': true,
          'storedPhotosReadable': true,
        },
      });
      await writePrivate(File('${fixture['workDir']}/browser-session.json'), {
        'email': fixture['email'],
        'password': fixture['password'],
        'recordId': recordId,
      });
      printOnFailure(
        'PHONE: real AVD return, required evidence, duplicate retries and durable receipt passed',
      );
    },
    skip: !const bool.fromEnvironment('RENTAL_EMULATOR_E2E'),
    timeout: const Timeout(Duration(minutes: 7)),
  );
}
