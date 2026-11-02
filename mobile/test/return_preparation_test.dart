import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:rental_check/app_scope.dart';
import 'package:rental_check/data/evidence_drafts.dart';
import 'package:rental_check/data/rental_api.dart';
import 'package:rental_check/models/rental_models.dart';
import 'package:rental_check/screens/evidence_screen.dart';
import 'package:rental_check/state/rental_controller.dart';
import 'package:rental_check/ui/photo_guidance.dart';
import 'package:rental_check/ui/components.dart';
import 'test_support.dart';

// Describe only one fixture rental's return evidence and a new item-review contract, with no real credentials or photos.
Json uploadFixture() => {
  'id': 'record-1',
  'revision': 2,
  'vinSuffix': 'ST0001',
  'photos': [
    for (final c in EvidenceCategory.values)
      {'id': 'photo-${c.name}', 'phase': 'return', 'category': c.name},
  ],
  'usageMemory': {
    'luggageMemos': [
      {'space': 'TRUNK', 'item': '캐리어'},
    ],
    'luggageReviewRequired': true,
  },
  'checks': {
    'windows': true,
    'belongings': true,
    'exterior': true,
    'collectedSpaces': [],
    'note': '',
  },
  'phase': 'return',
  'expiresAtMillis': DateTime.now()
      .add(const Duration(hours: 1))
      .millisecondsSinceEpoch,
};

void main() {
  // Exercise observable readiness, framing and restart behavior rather than duplicating serialization implementation details.
  test('general photo checks do not acknowledge each reviewed item', () {
    final raw = uploadFixture();
    expect(UploadSession.fromJson(raw).ready, isFalse);
    raw['checks']['collectedSpaces'] = ['TRUNK'];
    expect(UploadSession.fromJson(raw).ready, isTrue);
    raw['usageMemory']['luggageReviewRequired'] = false;
    raw['checks']['collectedSpaces'] = [];
    expect(UploadSession.fromJson(raw).ready, isTrue);
  });
  testWidgets(
    'capture instructions and honest missing-photo comparison render',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Column(
              children: [
                const PhotoGuidance(EvidenceCategory.exterior),
                TextButton(
                  onPressed: () => showPhotoComparison(
                    tester.element(find.byType(PhotoGuidance)),
                    EvidenceCategory.exterior,
                    [],
                    [],
                    null,
                  ),
                  child: const Text('compare'),
                ),
              ],
            ),
          ),
        ),
      );
      await tester.tap(find.text('촬영 구도 보기'));
      await tester.pumpAndSettle();
      expect(find.textContaining('구도 예시 도식'), findsOneWidget);
      await tester.tap(find.text('compare'));
      await tester.pumpAndSettle();
      expect(find.text('인수 사진이 없어요.'), findsOneWidget);
      expect(find.text('반납 사진이 없어요.'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'offline screen restores unsent checks and cannot enable final submission',
    (tester) async {
      final store = MemoryStore(), raw = uploadFixture();
      raw['photos'] = [];
      final api = RentalApi(
        'https://rental.test',
        client: MockClient((_) async => throw http.ClientException('offline')),
      )..accountToken = 'existing-account';
      final controller = RentalController(api: api, store: store)
        ..user = AccountUser.fromJson(userJson);
      final cache = EvidenceDrafts(store, api.base.origin, 'user-1');
      await cache.save('record-1_return', {
        'token': 'scoped-upload',
        'session': raw,
        'dirty': true,
        'checks': {
          'windows': true,
          'belongings': false,
          'exterior': false,
          'collectedSpaces': ['TRUNK'],
          'note': '오프라인 메모',
        },
        'photos': {},
      });
      await tester.pumpWidget(
        RentalScope(
          controller: controller,
          child: const MaterialApp(
            home: EvidenceScreen(token: 'scoped-upload'),
          ),
        ),
      );
      for (var i = 0; i < 8; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(find.textContaining('기기에 남겨 둔 사진·확인을 복원'), findsOneWidget);
      expect(find.textContaining('통신이 끊겨'), findsOneWidget);
      expect(
        (tester.widget<TextField>(find.byType(TextField))).controller!.text,
        '오프라인 메모',
      );
      final finalButton = find.widgetWithText(PrimaryAction, '반납 점검 최종 제출');
      expect(finalButton, findsOneWidget);
      expect(tester.widget<PrimaryAction>(finalButton).onPressed, isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
      api.close();
    },
  );
}
