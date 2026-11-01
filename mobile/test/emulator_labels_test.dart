import 'package:flutter_test/flutter_test.dart';
import 'package:rental_check/models/rental_models.dart';

// Keep the browser/phone record labels truthful while preserving compatibility with older server summaries.
void main() {
  final summary = <String, dynamic>{
    'id': 'test-record',
    'revision': 1,
    'vinSuffix': '000001',
    'isDemo': true,
    'photos': <dynamic>[],
    'checks': <String, dynamic>{},
  };
  test('emulator VIN explains that other fields are real SDK observations', () {
    final record = RentalRecord.fromJson({...summary, 'usesTestVin': true});
    expect(record.demoNotice, contains('에뮬레이터 테스트 VIN'));
    expect(record.demoNotice, contains('나머지 항목은 SDK'));
  });
  test('older synthetic records retain the synthetic-data label', () {
    final record = RentalRecord.fromJson(summary);
    expect(record.usesTestVin, isFalse);
    expect(record.demoNotice, contains('합성 시연 데이터'));
  });
}
