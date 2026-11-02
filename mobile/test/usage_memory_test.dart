import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/models/rental_models.dart';
import 'package:rental_check/state/rental_controller.dart';
import 'package:rental_check/ui/usage_memory_notice.dart';
import 'test_support.dart';
import 'dart:convert';

// Protect the distinction between observed places, explicit luggage marks and the real photo/check submission requirements.
void main() {
  testWidgets('reviewed item notes stay separate from sensor observations', (
    tester,
  ) async {
    // Show the user's saved item/location without an empty-history all-clear, even when neither a door opening nor a manual mark exists.
    final memory = UsageMemory.fromJson({
      'luggageMemos': [
        {'space': 'TRUNK', 'item': '캐리어'},
        {'space': 'UNKNOWN', 'item': '가방'},
      ],
    });
    expect(memory.luggageMemos, hasLength(1));
    await tester.pumpWidget(
      MaterialApp(home: Scaffold(body: UsageMemoryNotice(memory))),
    );
    expect(find.textContaining('내가 저장한 짐 메모: 트렁크 · 캐리어'), findsOneWidget);
    expect(find.textContaining('기록된 장소가 없어요'), findsNothing);
    expect(find.textContaining('물건 감지 결과가 아니에요'), findsOneWidget);
  });
  test('old and unknown memory never imply belongings are clear', () {
    // Older records and unknown area names must retain incomplete coverage and no invented location.
    final old = UsageMemory.fromJson(null);
    expect(old.opened, isEmpty);
    expect(old.monitoringGaps, isTrue);
    final memory = UsageMemory.fromJson({
      'openedSpaces': ['TRUNK', 'UNKNOWN', 'TRUNK'],
      'markedSpaces': ['REAR_RIGHT'],
    });
    expect(memory.opened, ['TRUNK']);
    expect(memory.marked, ['REAR_RIGHT']);
  });
  testWidgets('return reminders distinguish observation from explicit marks', (
    tester,
  ) async {
    // The user's luggage location and a different observed door must both appear without an automatic all-clear message.
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: UsageMemoryNotice(
            UsageMemory.fromJson({
              'openedSpaces': ['REAR_RIGHT'],
              'markedSpaces': ['TRUNK'],
              'monitoringGaps': true,
            }),
          ),
        ),
      ),
    );
    expect(find.textContaining('열림을 관측한 곳: 뒤쪽 오른쪽'), findsOneWidget);
    expect(find.textContaining('짐을 뒀다고 표시한 곳: 트렁크'), findsOneWidget);
    expect(find.textContaining('관측하지 못한 동작'), findsOneWidget);
  });
  test('fresh return phase replaces stale pickup before evidence', () async {
    // A vehicle return saved while the phone screen was open must lead to return photos and its latest memory.
    final phases = <String>[];
    final api = RentalApi(
      'https://rental.example.com',
      client: MockClient((r) async {
        if (r.method == 'GET') {
          return jsonResponse({
            'id': 'record-1',
            'revision': 2,
            'vinSuffix': '000001',
            'photos': [],
            'checks': <String, dynamic>{},
            'returnAtMillis': 200,
          });
        }
        phases.add((jsonDecode(r.body) as Json)['phase'] as String);
        return jsonResponse({
          'uploadUrl': 'https://rental.example.com/upload#token=${'a' * 43}',
        });
      }),
    )..accountToken = 'mock-account';
    final state = RentalController(api: api, store: MemoryStore());
    await state.beginEvidence(
      RentalRecord.fromJson({
        'id': 'record-1',
        'revision': 1,
        'vinSuffix': '000001',
        'photos': [],
        'checks': <String, dynamic>{},
      }),
    );
    expect(phases, ['return']);
    state.dispose();
  });
  test('a newly submitted record does not create another photo grant', () async {
    // Recover the latest server completion instead of permitting an outdated screen to reopen editable evidence.
    final api = RentalApi(
      'https://rental.example.com',
      client: MockClient((r) async {
        expect(r.method, 'GET');
        return jsonResponse({
          'id': 'record-1',
          'revision': 2,
          'vinSuffix': '000001',
          'photos': [],
          'checks': <String, dynamic>{},
          'submittedAtMillis': 300,
          'receiptId': 'receipt-1',
        });
      }),
    )..accountToken = 'mock-account';
    final state = RentalController(api: api, store: MemoryStore());
    await expectLater(
      state.beginEvidence(
        RentalRecord.fromJson({
          'id': 'record-1',
          'revision': 1,
          'vinSuffix': '000001',
          'photos': [],
          'checks': <String, dynamic>{},
        }),
      ),
      throwsA(isA<ApiException>()),
    );
    state.dispose();
  });
}
