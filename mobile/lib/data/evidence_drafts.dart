import 'dart:convert';
import 'package:uuid/uuid.dart';
import '../models/rental_models.dart';
import 'session_store.dart';

class EvidenceDrafts {
  // Bind encrypted drafts to one server and account, so another signed-in renter cannot resume the previous person's photos.
  EvidenceDrafts(this.store, String origin, String owner)
    : scope =
          'evidence_${Uri.encodeComponent(origin)}_${Uri.encodeComponent(owner)}';
  final SessionStore store;
  final String scope;
  static const chunkSize = 128 * 1024;
  static Future<void> _pending = Future.value();

  // Serialize writes from UI edits and photo selection, committing the manifest only after every encrypted chunk is durable.
  Future<void> save(String id, Json value) {
    final snapshot = jsonEncode({
      ...value,
      'updatedAt': DateTime.now().millisecondsSinceEpoch,
    });
    final operation = _pending
        .catchError((Object _) {})
        .then((_) => _save(id, snapshot));
    _pending = operation.catchError((Object _) {});
    return operation;
  }

  // Use a new generation for each save; an interrupted write leaves the previous manifest and complete draft recoverable.
  Future<void> _save(String id, String text) async {
    if (text.length > 40 * 1024 * 1024) {
      throw const FormatException('임시 사진 용량이 커요. 한 항목의 사진을 먼저 전송해 주세요.');
    }
    final key = '${scope}_$id';
    await _recoverIncomplete(key);
    final old = await store.read(key);
    final generation = const Uuid().v4(), chunks = <String>[];
    for (var start = 0; start < text.length;) {
      var end = (start + chunkSize).clamp(0, text.length);
      if (end < text.length &&
          text.codeUnitAt(end - 1) >= 0xD800 &&
          text.codeUnitAt(end - 1) <= 0xDBFF) {
        end--;
      }
      chunks.add(text.substring(start, end));
      start = end;
    }
    final count = chunks.length;
    final index = await list();
    if (!index.contains(id) && index.length >= 5) {
      throw const FormatException('임시 작성은 최대 5건이에요. 이전 작성을 전송하거나 삭제해 주세요.');
    }
    await store.write('${scope}_index', jsonEncode({...index, id}.toList()));
    await store.write(
      '${key}_journal',
      jsonEncode({
        'generation': generation,
        'count': count,
        'previous': old == null ? null : jsonDecode(old),
      }),
    );
    try {
      for (var i = 0; i < count; i++) {
        await store.write('${key}_${generation}_$i', chunks[i]);
      }
      await store.write(
        key,
        jsonEncode({'generation': generation, 'count': count}),
      );
    } catch (_) {
      await _recoverIncomplete(key);
      rethrow;
    }
    if (old != null) await _removeChunks(key, jsonDecode(old) as Json);
    await store.delete('${key}_journal');
  }

  // Read only committed generations and expire old private photos; missing chunks are reported instead of silently dropping a selected picture.
  Future<Json?> read(String id) async {
    await _pending;
    final key = '${scope}_$id';
    await _recoverIncomplete(key);
    final raw = await store.read(key);
    if (raw == null) return null;
    final manifest = jsonDecode(raw) as Json, parts = <String>[];
    final count = manifest['count'] as int;
    if (count < 1 || count > 321) {
      throw const FormatException('임시 저장 정보를 확인하지 못했어요.');
    }
    for (var i = 0; i < count; i++) {
      final part = await store.read('${key}_${manifest['generation']}_$i');
      if (part == null) {
        throw const FormatException('임시 사진 일부를 복구하지 못했어요. 저장 공간을 확인해 주세요.');
      }
      parts.add(part);
    }
    final value = jsonDecode(parts.join()) as Json;
    if (DateTime.now().millisecondsSinceEpoch - (value['updatedAt'] as int) >
        const Duration(days: 7).inMilliseconds) {
      await remove(id);
      return null;
    }
    return value;
  }

  // List only opaque rental/phase keys within the authenticated owner's namespace, without exposing another account's pending work.
  Future<List<String>> list() async {
    final values =
            (jsonDecode(await store.read('${scope}_index') ?? '[]') as List)
                .cast<String>(),
        result = <String>[];
    for (final id in values) {
      if (await store.read('${scope}_$id') != null ||
          await store.read('${scope}_${id}_journal') != null) {
        result.add(id);
      }
    }
    return result;
  }

  // Remove encrypted temporary data after an acknowledged submission or an explicit user discard, never deleting server evidence.
  Future<void> remove(String id) async {
    await _pending;
    final key = '${scope}_$id';
    await _recoverIncomplete(key);
    final raw = await store.read(key);
    await store.delete(key);
    if (raw != null) await _removeChunks(key, jsonDecode(raw) as Json);
    await store.write(
      '${scope}_index',
      jsonEncode((await list()).where((v) => v != id).toList()),
    );
  }

  // Recover a process death between chunk writes and manifest commit, retiring both abandoned new chunks and superseded old generations.
  Future<void> _recoverIncomplete(String key) async {
    final raw = await store.read('${key}_journal');
    if (raw == null) return;
    final journal = jsonDecode(raw) as Json;
    final committed = await store.read(key);
    if (committed == null ||
        (jsonDecode(committed) as Json)['generation'] !=
            journal['generation']) {
      await _removeChunks(key, journal);
    } else if (journal['previous'] != null) {
      await _removeChunks(key, journal['previous'] as Json);
    }
    await store.delete('${key}_journal');
  }

  // Retire a superseded generation only after the new manifest is committed; no raw image file is written outside secure storage.
  Future<void> _removeChunks(String key, Json manifest) async {
    final count = manifest['count'] as int;
    if (count < 1 || count > 321) {
      throw const FormatException('임시 자료 정리 정보를 확인해 주세요.');
    }
    for (var i = 0; i < count; i++) {
      await store.delete('${key}_${manifest['generation']}_$i');
    }
  }
}
