import 'dart:typed_data';
import 'rental_api.dart';

void validatePhoto(Uint8List bytes) {
  if (bytes.length > 8 * 1024 * 1024) {
    throw const ApiException('사진은 한 장에 8MB 이하로 선택해 주세요.');
  }
  final jpeg =
      bytes.length >= 3 &&
      bytes[0] == 255 &&
      bytes[1] == 216 &&
      bytes[2] == 255;
  final png =
      bytes.length >= 8 &&
      bytes.take(8).join(',') == '137,80,78,71,13,10,26,10';
  final webp =
      bytes.length >= 12 &&
      String.fromCharCodes(bytes.take(4)) == 'RIFF' &&
      String.fromCharCodes(bytes.sublist(8, 12)) == 'WEBP';
  if (!jpeg && !png && !webp) {
    throw const ApiException(
      'JPEG·PNG·WebP 사진을 선택해 주세요. HEIC 사진은 JPEG로 변환한 뒤 전송해 주세요.',
    );
  }
}
