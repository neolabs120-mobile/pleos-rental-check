import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import '../models/rental_models.dart';

class ApiException implements Exception {
  const ApiException(this.message, {this.status = 0});
  final String message;
  final int status;
  @override
  String toString() => message;
}

enum Auth { none, account, photo }

class RentalApi {
  RentalApi(String server, {http.Client? client})
    : base = validateServer(server),
      _client = client ?? http.Client();
  Uri base;
  final http.Client _client;
  String? accountToken;
  VoidCallback? onAccountExpired;
  static Uri validateServer(String value, {bool allowHttp = kDebugMode}) {
    final uri = Uri.tryParse(value.trim());
    if (uri == null ||
        !uri.hasAuthority ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        (uri.path.isNotEmpty && uri.path != '/') ||
        !(uri.scheme == 'https' || (allowHttp && uri.scheme == 'http'))) {
      throw const FormatException('서버 주소를 확인해 주세요. 배포 앱에는 HTTPS 주소가 필요해요.');
    }
    return uri.replace(path: '');
  }

  Future<http.Response> _send(
    String method,
    String path, {
    Json? body,
    Auth auth = Auth.account,
    String? photoToken,
  }) async {
    final req = http.Request(method, base.resolve(path))
      ..followRedirects = false;
    req.headers['Accept'] = 'application/json';
    req.headers['Cache-Control'] = 'no-store';
    if (auth != Auth.none) {
      if (accountToken == null) {
        throw const ApiException('로그인이 필요해요.', status: 401);
      }
      req.headers['Authorization'] =
          'Bearer ${auth == Auth.photo ? photoToken : accountToken}';
      if (auth == Auth.photo) req.headers['X-Account-Token'] = accountToken!;
    }
    if (body != null) {
      req.headers['Content-Type'] = 'application/json';
      req.body = jsonEncode(body);
    }
    try {
      final response = await (() async =>
              http.Response.fromStream(await _client.send(req)))()
          .timeout(const Duration(seconds: 30));
      if (response.statusCode < 200 || response.statusCode >= 300) {
        String message = '요청을 처리하지 못했어요. 다시 시도해 주세요.';
        try {
          final decoded = jsonDecode(utf8.decode(response.bodyBytes)) as Json;
          if (decoded['detail'] is String) {
            message = decoded['detail'] as String;
          } else if (response.statusCode == 422) {
            message = '입력값의 형식과 길이를 확인해 주세요.';
          }
        } catch (_) {
          /* A proxy can return a non-JSON error page. */
        }
        if (response.statusCode == 401 && auth == Auth.account) {
          onAccountExpired?.call();
        }
        throw ApiException(message, status: response.statusCode);
      }
      return response;
    } on TimeoutException {
      throw const ApiException('응답이 늦어지고 있어요. 연결을 확인하고 다시 시도해 주세요.');
    } on http.ClientException {
      throw const ApiException('서버에 연결할 수 없어요. 네트워크와 서버 주소를 확인해 주세요.');
    }
  }

  Future<Json> request(
    String method,
    String path, {
    Json? body,
    Auth auth = Auth.account,
    String? photoToken,
  }) async {
    final r = await _send(
      method,
      path,
      body: body,
      auth: auth,
      photoToken: photoToken,
    );
    try {
      return jsonDecode(utf8.decode(r.bodyBytes)) as Json;
    } on FormatException {
      throw const ApiException('서버 응답 형식을 확인하지 못했어요.');
    }
  }

  Future<Json> signup(String name, String email, String password) => request(
    'POST',
    '/api/auth/signup',
    auth: Auth.none,
    body: {
      'nickname': name.trim(),
      'email': email.trim(),
      'password': password,
      'termsAccepted': true,
      'privacyAccepted': true,
    },
  );
  Future<Json> login(String email, String password) => request(
    'POST',
    '/api/auth/login',
    auth: Auth.none,
    body: {'email': email.trim(), 'password': password},
  );
  Future<void> logout() async {
    await request('POST', '/api/auth/logout', body: {});
  }

  Future<AccountUser> me() async =>
      AccountUser.fromJson(await request('GET', '/api/account/me'));
  Future<List<Booking>> bookings() async =>
      ((await request('GET', '/api/account/bookings'))['bookings'] as List)
          .map((b) => Booking.fromJson(b as Json))
          .toList();
  Future<void> claim(String number, String code) async {
    await request(
      'POST',
      '/api/account/bookings/claim',
      body: {'bookingNumber': number.trim(), 'claimCode': code.trim()},
    );
  }

  Future<void> approve(String token, String bookingId) async {
    await request(
      'POST',
      '/api/account/pairings/approve',
      body: {'token': token, 'bookingId': bookingId},
    );
  }

  Future<RentalRecord> record(String id) async => RentalRecord.fromJson(
    await request('GET', '/api/account/rentals/${Uri.encodeComponent(id)}'),
  );
  Future<Json> photoLink(String id, String phase) => request(
    'POST',
    '/api/account/rentals/${Uri.encodeComponent(id)}/photo-links',
    body: {'phase': phase},
  );
  Future<UploadSession> uploadSession(String token) async =>
      UploadSession.fromJson(
        await request(
          'GET',
          '/api/upload/session',
          auth: Auth.photo,
          photoToken: token,
        ),
      );
  Future<void> upload(
    String token,
    EvidenceCategory category,
    String requestId,
    Uint8List bytes,
  ) async {
    if (bytes.length > 8 * 1024 * 1024) {
      throw const ApiException('사진은 한 장에 8MB 이하로 선택해 주세요.');
    }
    await request(
      'POST',
      '/api/upload/photos',
      auth: Auth.photo,
      photoToken: token,
      body: {
        'category': category.name,
        'requestId': requestId,
        'imageBase64': base64Encode(bytes),
      },
    );
  }

  Future<void> deletePhoto(String token, String id) async {
    await request(
      'DELETE',
      '/api/upload/photos/${Uri.encodeComponent(id)}',
      auth: Auth.photo,
      photoToken: token,
    );
  }

  Future<void> saveChecks(String token, ManualChecks checks) async {
    await request(
      'PUT',
      '/api/upload/checks',
      auth: Auth.photo,
      photoToken: token,
      body: checks.toJson(),
    );
  }

  Future<Json> submit(String token, int revision) => request(
    'POST',
    '/api/upload/submit',
    auth: Auth.photo,
    photoToken: token,
    body: {'revision': revision},
  );
  Future<Uint8List> photo(String id) async =>
      (await _send(
        'GET',
        '/api/account/photos/${Uri.encodeComponent(id)}',
      )).bodyBytes;
  void close() => _client.close();
}
