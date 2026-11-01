import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:rental_check/data/connection_link.dart';
import 'package:rental_check/data/photo_validation.dart';
import 'package:rental_check/data/rental_api.dart';

void main() {
  final server = Uri.parse('https://rental.example.com');
  final token = 'a' * 43;
  test('pairing QR from configured origin is accepted', () {
    final link = ConnectionLink.parse('$server/account#pair=$token', server);
    expect(link.kind, ConnectionKind.pairing);
    expect(link.token, token);
  });
  test('photo grant is distinct from pairing', () {
    expect(
      ConnectionLink.parse('$server/upload#token=$token', server).kind,
      ConnectionKind.upload,
    );
  });
  for (final raw in [
    'https://other.example.com/account#pair=$token',
    'http://rental.example.com/account#pair=$token',
    'https://rental.example.com:8443/account#pair=$token',
    'ftp://rental.example.com/account#pair=$token',
    '/account#pair=$token',
    '$server/account#pair=short',
    '$server/account#pair=$token&other=value',
    '$server/account?token=$token#pair=$token',
    'https://user:pass@rental.example.com/account#pair=$token',
  ]) {
    test(
      'reject untrusted or ambiguous QR ${Uri.tryParse(raw)?.scheme ?? 'invalid'} ${raw.length}',
      () {
        expect(() => ConnectionLink.parse(raw, server), throwsFormatException);
      },
    );
  }
  test(
    'release endpoint requires HTTPS without embedded credentials or paths',
    () {
      for (final value in [
        'http://localhost:8787',
        'https://x.example/api',
        'https://u:p@x.example',
        'https://x.example?key=a',
      ]) {
        expect(
          () => RentalApi.validateServer(value, allowHttp: false),
          throwsFormatException,
        );
      }
      expect(
        RentalApi.validateServer('https://x.example/').toString(),
        'https://x.example',
      );
    },
  );
  test(
    'JPEG PNG and WebP can be sent; server still validates the full image',
    () {
      for (final bytes in [
        [255, 216, 255, 0],
        [137, 80, 78, 71, 13, 10, 26, 10],
        'RIFF0000WEBP'.codeUnits,
      ]) {
        expect(() => validatePhoto(Uint8List.fromList(bytes)), returnsNormally);
      }
    },
  );
  test(
    'reject HEIC, empty and oversized photos before network transmission',
    () {
      for (final bytes in [
        Uint8List(0),
        Uint8List.fromList('ftypheic'.codeUnits),
        Uint8List(8 * 1024 * 1024 + 1),
      ]) {
        expect(() => validatePhoto(bytes), throwsA(isA<ApiException>()));
      }
    },
  );
}
