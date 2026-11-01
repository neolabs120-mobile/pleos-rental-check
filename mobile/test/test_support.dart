import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:rental_check/data/session_store.dart';

class MemoryStore implements SessionStore {
  final values = <String, String>{};
  @override
  Future<String?> read(String key) async => values[key];
  @override
  Future<void> write(String key, String value) async {
    values[key] = value;
  }

  @override
  Future<void> delete(String key) async {
    values.remove(key);
  }
}

http.Response jsonResponse(Object value, [int status = 200]) =>
    http.Response.bytes(
      utf8.encode(jsonEncode(value)),
      status,
      headers: {'content-type': 'application/json'},
    );
const userJson = {
  'id': 'user-1',
  'email': 'renter@example.com',
  'nickname': '이용자',
};
const bookingJson = {
  'id': 'booking-1',
  'bookingNumber': 'RC-TEST',
  'vehicleName': '시험 차량',
  'vinSuffix': 'ST0001',
  'isDemo': true,
  'rentalId': null,
  'submittedAtMillis': null,
};
