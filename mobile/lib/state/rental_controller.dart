import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import '../data/connection_link.dart';
import '../data/rental_api.dart';
import '../data/session_store.dart';
import '../models/rental_models.dart';

class RentalController extends ChangeNotifier {
  RentalController({required this.api, required this.store}) {
    api.onAccountExpired = () {
      user = null;
      bookings = [];
      api.accountToken = null;
      pendingLink = null;
      unawaited(store.delete('session').catchError((Object _) {}));
      notifyListeners();
      onSessionExpired?.call();
    };
  }
  final RentalApi api;
  final SessionStore store;
  AccountUser? user;
  List<Booking> bookings = [];
  ConnectionLink? pendingLink;
  bool restoring = true;
  String? startupError;
  VoidCallback? onSessionExpired;
  Future<void> restore() async {
    try {
      final endpoint = await store.read('server');
      if (endpoint != null) api.base = RentalApi.validateServer(endpoint);
      final raw = await store.read('session');
      if (raw != null) {
        final session = jsonDecode(raw) as Json;
        if (session['origin'] == api.base.origin &&
            DateTime.now().millisecondsSinceEpoch <
                (session['expiresAtMillis'] as num)) {
          api.accountToken = session['token'] as String;
          user = await api.me();
          bookings = await api.bookings();
        } else {
          await store.delete('session');
        }
      }
    } catch (e) {
      startupError = e is ApiException
          ? e.message
          : '저장된 로그인 정보를 불러오지 못했어요. 다시 로그인해 주세요.';
      api.accountToken = null;
      user = null;
    } finally {
      restoring = false;
      notifyListeners();
    }
  }

  Future<void> login(String email, String password) async {
    final r = await api.login(email, password);
    await store.write(
      'session',
      jsonEncode({
        'token': r['token'],
        'origin': api.base.origin,
        'expiresAtMillis': r['expiresAtMillis'],
      }),
    );
    api.accountToken = r['token'] as String;
    user = AccountUser.fromJson(r['user'] as Json);
    startupError = null;
    bookings = [];
    notifyListeners();
  }

  Future<void> refreshBookings() async {
    bookings = await api.bookings();
    notifyListeners();
  }

  Future<void> logout() async {
    await api.logout();
    await _clearSession();
  }

  Future<void> _clearSession() async {
    await store.delete('session');
    api.accountToken = null;
    user = null;
    bookings = [];
    pendingLink = null;
    notifyListeners();
  }

  Future<void> configureServer(String value) async {
    final endpoint = RentalApi.validateServer(value);
    if (endpoint.origin == api.base.origin) return;
    // Never carry account credentials to a newly configured endpoint.
    await _clearSession();
    await store.write('server', endpoint.toString());
    api.base = endpoint;
    startupError = null;
    notifyListeners();
  }

  void acceptLink(String raw) {
    pendingLink = ConnectionLink.parse(raw, api.base);
    notifyListeners();
  }

  void clearLink() {
    pendingLink = null;
    notifyListeners();
  }

  Future<String> beginEvidence(RentalRecord record) async {
    // Re-read the vehicle's latest phase so an open pickup screen cannot issue pickup evidence after a return has been saved.
    final latest = await api.record(record.id);
    if (latest.isSubmitted) {
      throw const ApiException('이미 제출한 점검 기록이에요. 내 기록에서 확인해 주세요.');
    }
    final link = await api.photoLink(
      latest.id,
      latest.returnAt == null ? 'pickup' : 'return',
    );
    return ConnectionLink.parse(link['uploadUrl'] as String, api.base).token;
  }

  @override
  void dispose() {
    api.close();
    super.dispose();
  }
}
