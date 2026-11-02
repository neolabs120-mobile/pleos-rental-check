import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:rental_check/data/evidence_drafts.dart';
import 'package:rental_check/data/session_store.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/state/rental_controller.dart';

class MemoryStorage implements SessionStore {
  final values = <String, String>{};
  int? remainingWrites;
  @override
  // Model encrypted-store lookup without bringing platform credentials into a deterministic interrupted-write test.
  Future<String?> read(String key) async => values[key];
  @override
  // Fail during a selected generation to verify that a previously committed photo draft remains complete.
  Future<void> write(String key, String value) async {
    if (remainingWrites != null && remainingWrites! <= 0) {
      throw StateError('storage interrupted');
    }
    if (remainingWrites != null) remainingWrites = remainingWrites! - 1;
    values[key] = value;
  }

  @override
  // Deleting a temporary generation leaves unrelated owners and the earlier committed manifest untouched.
  Future<void> delete(String key) async => values.remove(key);
}

// Keep real-photo bytes out of fixtures while exercising multi-chunk persistence and stable upload-request identifiers.
Map<String, dynamic> draft(String text) => {
  'token': 'scoped-upload-token',
  'checks': {'note': text},
  'photos': {
    'windows': {'bytes': 'YWJj', 'requestId': 'same-request'},
  },
  'dirty': true,
};

void main() {
  // Test persistence independently of UI timing so crashes, owner changes and retries cannot appear successful merely because a widget stayed in memory.
  test(
    'committed draft survives reconstruction and keeps request IDs',
    () async {
      final store = MemoryStorage(),
          first = EvidenceDrafts(
            MemoryStorage(),
            'https://unused.test',
            'unused',
          );
      expect(await first.list(), isEmpty);
      await EvidenceDrafts(
        store,
        'https://rental.test',
        'owner',
      ).save('rental_return', draft('확인 중'));
      final restored = await EvidenceDrafts(
        store,
        'https://rental.test',
        'owner',
      ).read('rental_return');
      expect(restored!['photos']['windows']['requestId'], 'same-request');
      expect(restored['checks']['note'], '확인 중');
    },
  );
  test('failed new generation preserves previous complete draft', () async {
    final store = MemoryStorage(), key = 'rental_return';
    final cache = EvidenceDrafts(store, 'https://rental.test', 'owner');
    await cache.save(key, draft('original'));
    store.remainingWrites = 3;
    await expectLater(
      cache.save(key, draft('x' * (EvidenceDrafts.chunkSize * 2))),
      throwsStateError,
    );
    store.remainingWrites = null;
    expect((await cache.read(key))!['checks']['note'], 'original');
  });
  test(
    'process death journal recovers either side of the manifest commit',
    () async {
      // A process can die without throwing to the writer; recovery must retain the committed generation and remove abandoned private chunks.
      final store = MemoryStorage(),
          cache = EvidenceDrafts(
            MemoryStorage(),
            'https://unused.test',
            'unused',
          );
      expect(await cache.list(), isEmpty);
      final active = EvidenceDrafts(store, 'https://rental.test', 'owner');
      await active.save('rental_return', draft('old'));
      final key = '${active.scope}_rental_return',
          previous = jsonDecode(store.values[key]!) as Map<String, dynamic>;
      store.values['${key}_journal'] = jsonEncode({
        'generation': 'abandoned',
        'count': 1,
        'previous': previous,
      });
      store.values['${key}_abandoned_0'] = 'unfinished';
      expect((await active.read('rental_return'))!['checks']['note'], 'old');
      expect(store.values.containsKey('${key}_abandoned_0'), isFalse);
      store.values['${key}_journal'] = jsonEncode({
        'generation': 'committed',
        'count': 1,
        'previous': previous,
      });
      store.values['${key}_committed_0'] = jsonEncode({
        ...draft('new'),
        'updatedAt': DateTime.now().millisecondsSinceEpoch,
      });
      store.values[key] = jsonEncode({'generation': 'committed', 'count': 1});
      expect((await active.read('rental_return'))!['checks']['note'], 'new');
      expect(
        store.values.containsKey('${key}_${previous['generation']}_0'),
        isFalse,
      );
      expect(store.values.containsKey('${key}_journal'), isFalse);
    },
  );
  test('photos cannot cross server account or rental boundaries', () async {
    final store = MemoryStorage();
    await EvidenceDrafts(
      store,
      'https://rental.test',
      'owner',
    ).save('rental_return', draft('private'));
    for (final cache in [
      EvidenceDrafts(store, 'https://rental.test', 'other'),
      EvidenceDrafts(store, 'https://other.test', 'owner'),
    ]) {
      expect(await cache.list(), isEmpty);
      expect(await cache.read('rental_return'), isNull);
    }
    expect(
      await EvidenceDrafts(
        store,
        'https://rental.test',
        'owner',
      ).read('another_return'),
      isNull,
    );
  });
  test(
    'large multilingual note survives chunk boundaries and explicit removal',
    () async {
      final store = MemoryStorage(), note = '가방🧳' * 50000;
      final cache = EvidenceDrafts(store, 'https://rental.test', 'owner');
      await cache.save('rental_return', draft(note));
      expect((await cache.read('rental_return'))!['checks']['note'], note);
      await cache.remove('rental_return');
      expect(await cache.list(), isEmpty);
      expect(await cache.read('rental_return'), isNull);
    },
  );
  test(
    'offline restart retains only unexpired cached authenticated identity',
    () async {
      final store = MemoryStorage();
      store.values['session'] = jsonEncode({
        'origin': 'https://rental.test',
        'token': 'existing-token',
        'expiresAtMillis': DateTime.now()
            .add(const Duration(hours: 1))
            .millisecondsSinceEpoch,
        'user': {
          'id': 'owner',
          'email': 'fixture@example.test',
          'nickname': '시험',
        },
      });
      final api = RentalApi(
        'https://rental.test',
        client: MockClient((_) async => throw http.ClientException('offline')),
      );
      final controller = RentalController(api: api, store: store);
      await controller.restore();
      expect(controller.user!.id, 'owner');
      expect(controller.startupError, contains('서버에 연결하지 못했어요'));
      controller.dispose();
      api.close();
    },
  );
  test(
    'authentication rejection cannot masquerade as offline recovery',
    () async {
      final store = MemoryStorage();
      store.values['session'] = jsonEncode({
        'origin': 'https://rental.test',
        'token': 'expired-server-token',
        'expiresAtMillis': DateTime.now()
            .add(const Duration(hours: 1))
            .millisecondsSinceEpoch,
        'user': {
          'id': 'owner',
          'email': 'fixture@example.test',
          'nickname': '시험',
        },
      });
      final api = RentalApi(
        'https://rental.test',
        client: MockClient(
          (_) async => http.Response('{"detail":"expired"}', 401),
        ),
      );
      final controller = RentalController(api: api, store: store);
      await controller.restore();
      expect(controller.user, isNull);
      expect(api.accountToken, isNull);
      controller.dispose();
      api.close();
    },
  );
}
