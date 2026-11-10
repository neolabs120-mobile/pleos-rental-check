import '../ui/battery_return_notice.dart';
import 'dart:convert';
import '../data/evidence_drafts.dart';
import '../ui/photo_guidance.dart';
import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:uuid/uuid.dart';
import '../app_scope.dart';
import '../data/photo_validation.dart';
import '../data/rental_api.dart';
import '../models/rental_models.dart';
import '../ui/components.dart';
import '../ui/usage_memory_notice.dart';
import '../ui/secure_photo.dart';
import 'receipt_screen.dart';

class PhotoDraft {
  PhotoDraft(this.bytes, {String? request})
    : requestId = request ?? const Uuid().v4();
  final Uint8List bytes;
  // Stable across failed attempts; the server can safely deduplicate a retry.
  final String requestId;
}

class EvidenceScreen extends StatefulWidget {
  const EvidenceScreen({super.key, required this.token});
  final String token;
  @override
  State<EvidenceScreen> createState() => _EvidenceScreenState();
}

class _EvidenceScreenState extends State<EvidenceScreen> with ActionState {
  late String token = widget.token;
  UploadSession? session;
  final picker = ImagePicker();
  final drafts = <EvidenceCategory, PhotoDraft>{};
  final checked = <EvidenceCategory, bool>{
    for (final c in EvidenceCategory.values) c: false,
  };
  final note = TextEditingController();
  bool dirty = false, leaving = false;
  late final Timer ticker;
  final collected = <String>{};
  Json? cachedSession;
  bool offline = false, restored = false;
  String? localMessage;
  Timer? debounce;
  EvidenceDrafts get cache {
    final state = RentalScope.of(context);
    return EvidenceDrafts(state.store, state.api.base.origin, state.user!.id);
  }

  String get draftKey => '${session!.record.id}_${session!.phase}';
  @override
  void initState() {
    super.initState();
    ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        run(() async {
          await restoreDraft();
          if (!offline) await recoverPhoto();
        });
      }
    });
  }

  @override
  void dispose() {
    ticker.cancel();
    debounce?.cancel();
    note.dispose();
    super.dispose();
  }

  // Reconcile only the active account's upload grant; network failure may use a committed local session without authorizing final submission.
  Future<void> load({bool syncChecks = false}) async {
    Json raw;
    try {
      raw = await RentalScope.of(context).api.request(
        'GET',
        '/api/upload/session',
        auth: Auth.photo,
        photoToken: token,
      );
      offline = false;
    } on ApiException catch (error) {
      if (error.status != 0 || cachedSession == null) rethrow;
      raw = cachedSession!;
      offline = true;
    }
    final loaded = UploadSession.fromJson(raw);
    if (!mounted) return;
    final phaseChanged = session != null && session!.phase != loaded.phase;
    setState(() {
      if (phaseChanged) drafts.clear();
      session = loaded;
      cachedSession = raw;
      if (syncChecks || phaseChanged) {
        for (final c in EvidenceCategory.values) {
          checked[c] = loaded.checks.checked(c);
        }
        collected
          ..clear()
          ..addAll(loaded.checks.collectedSpaces);
        note.text = loaded.checks.note;
        dirty = false;
      }
    });
  }

  // Preserve photos, stable upload identifiers and unsent checks in account-bound encrypted storage before claiming local recovery is available.
  Future<void> persistDraft() async {
    if (session == null || cachedSession == null || leaving) return;
    debounce?.cancel();
    await cache.save(draftKey, {
      'token': token,
      'session': cachedSession,
      'dirty': dirty,
      'checks': currentChecks.toJson(),
      'photos': {
        for (final e in drafts.entries)
          e.key.name: {
            'bytes': base64Encode(e.value.bytes),
            'requestId': e.value.requestId,
          },
      },
    });
    if (mounted) {
      setState(
        () => localMessage = '기기에 암호화해 임시 저장했어요. 아직 전송하지 않은 내용은 서버 접수와 별개예요.',
      );
    }
  }

  // Restore only the current owner's saved upload token, then reconcile with the server before permitting final submission.
  Future<void> restoreDraft() async {
    final index = await cache.list();
    Json? saved;
    for (final id in index) {
      final value = await cache.read(id);
      if (value?['token'] == token) {
        saved = value;
        break;
      }
    }
    cachedSession = saved?['session'] as Json?;
    await load(syncChecks: true);
    saved ??= await cache.read(draftKey);
    if (saved != null &&
        saved['session']['phase'] == session!.phase &&
        saved['session']['id'] == session!.record.id) {
      final checks = ManualChecks.fromJson(saved['checks'] as Json);
      setState(() {
        if (saved!['dirty'] == true) {
          for (final c in EvidenceCategory.values) {
            checked[c] = checks.checked(c);
          }
          collected
            ..clear()
            ..addAll(checks.collectedSpaces);
          note.text = checks.note;
          dirty = true;
        }
        for (final entry in (saved['photos'] as Json).entries) {
          final bytes = base64Decode(entry.value['bytes'] as String);
          validatePhoto(bytes);
          drafts[EvidenceCategory.values.byName(entry.key)] = PhotoDraft(
            bytes,
            request: entry.value['requestId'] as String,
          );
        }
        restored = true;
        localMessage = '기기에 남겨 둔 사진·확인을 복원했어요. 전송 상태를 확인해 주세요.';
      });
    }
    await persistDraft();
  }

  // Save the latest form after a short typing pause while surfacing a local-storage failure instead of promising recovery that did not occur.
  void queueDraft() {
    localMessage = '변경 내용을 기기에 임시 저장 중이에요.';
    debounce?.cancel();
    debounce = Timer(const Duration(milliseconds: 350), () async {
      try {
        await persistDraft();
      } catch (_) {
        if (mounted) {
          setState(
            () => localMessage =
                '기기 임시 저장에 실패했어요. 저장 공간을 확인하고 화면을 닫기 전에 다시 시도해 주세요.',
          );
        }
      }
    });
  }

  // Keep manual check state and item acknowledgements explicit; no SDK observation automatically checks these fields.
  ManualChecks get currentChecks => ManualChecks(
    windows: checked[EvidenceCategory.windows]!,
    belongings: checked[EvidenceCategory.belongings]!,
    exterior: checked[EvidenceCategory.exterior]!,
    note: note.text,
    collectedSpaces: collected.toList(),
  );

  // Recover a system-camera interruption explicitly, then commit the chosen category before promising that the photo can survive restart.
  Future<void> recoverPhoto() async {
    if (defaultTargetPlatform != TargetPlatform.android) return;
    final lost = await picker.retrieveLostData();
    if (lost.isEmpty || !mounted) return;
    if (lost.files?.isNotEmpty != true) {
      throw const ApiException('중단된 사진 선택을 복구하지 못했어요. 사진을 다시 선택해 주세요.');
    }
    final category = await showDialog<EvidenceCategory>(
      context: context,
      builder: (context) => SimpleDialog(
        title: const Text('복구한 사진의 항목을 선택해 주세요'),
        children: [
          for (final c in EvidenceCategory.values)
            SimpleDialogOption(
              onPressed: () => Navigator.pop(context, c),
              child: Text(c.label),
            ),
        ],
      ),
    );
    if (category == null || !mounted) return;
    final bytes = await lost.files!.first.readAsBytes();
    validatePhoto(bytes);
    if (mounted) setState(() => drafts[category] = PhotoDraft(bytes));
    await persistDraft();
  }

  // Validate and durably retain the selected image before network work, with one stable request ID for every retry.
  Future<void> pick(EvidenceCategory category, ImageSource source) => run(
    () async {
      try {
        final file = await picker.pickImage(
          source: source,
          maxWidth: 2048,
          maxHeight: 2048,
          imageQuality: 88,
          requestFullMetadata: false,
        );
        if (file == null) return;
        final bytes = await file.readAsBytes();
        validatePhoto(bytes);
        if (mounted) setState(() => drafts[category] = PhotoDraft(bytes));
        await persistDraft();
      } on PlatformException {
        throw const ApiException('사진을 열지 못했어요. 기기 설정에서 카메라·사진 접근 권한을 확인해 주세요.');
      }
    },
  );
  // Upload the committed draft idempotently; retain it on a failed response and remove it only after the server acknowledges storage.
  Future<void> send(EvidenceCategory category) => run(() async {
    await persistDraft();
    if (!mounted) return;
    final draft = drafts[category]!;
    await RentalScope.of(
      context,
    ).api.upload(token, category, draft.requestId, draft.bytes);
    if (!mounted) return;
    setState(() => drafts.remove(category));
    await load();
    await persistDraft();
    if (mounted) toast(context, '${category.label} 사진을 서버에 저장했어요.');
  });
  // Keep user check marks locally first, and distinguish an offline draft from an acknowledged server checklist.
  Future<void> save() => run(() async {
    await persistDraft();
    if (offline) {
      if (mounted) toast(context, '기기에 임시 저장했어요. 연결 후 서버에 전송해 주세요.');
      return;
    }
    if (!mounted) return;
    await RentalScope.of(context).api.saveChecks(token, currentChecks);
    if (!mounted) return;
    await load(syncChecks: true);
    await persistDraft();
    if (mounted) toast(context, '직접 확인과 메모를 저장했어요.');
  });
  // Preserve pending edits before obtaining a fresh owner-authorized photo link and reconciling a possibly changed inspection phase.
  Future<void> renew() => run(() async {
    await persistDraft();
    if (!mounted) return;
    final state = RentalScope.of(context);
    final record = await state.api.record(session!.record.id);
    final renewed = await state.beginEvidence(record);
    token = renewed;
    if (mounted) {
      await load();
      await persistDraft();
    }
  });
  Future<void> submit() async {
    // Confirm a complete return, recover a lost receipt response, and carry the record's test VIN warning into the result screen.
    final yes = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('반납 점검을 제출할까요?'),
        content: const Text('제출한 사진과 직접 확인 기록을 보관해요. 제출 후에는 수정할 수 없어요.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('더 확인하기'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('최종 제출'),
          ),
        ],
      ),
    );
    if (yes != true || !mounted) return;
    await run(() async {
      final state = RentalScope.of(context);
      Json receipt;
      try {
        receipt = await state.api.submit(token, session!.record.revision);
      } on ApiException {
        // A response can be lost after a successful commit. Confirm with the owner API.
        final record = await state.api.record(session!.record.id);
        if (!record.isSubmitted) rethrow;
        receipt = {
          'id': record.id,
          'receiptId': record.receiptId,
          'submittedAtMillis': record.submittedAt!.millisecondsSinceEpoch,
        };
      }
      if (!mounted) return;
      if (receipt['receiptId'] == null ||
          receipt['submittedAtMillis'] == null) {
        throw const ApiException('서버 접수 응답을 확인하지 못했어요. 내 기록에서 다시 확인해 주세요.');
      }
      debounce?.cancel();
      try {
        await cache.remove(draftKey);
      } catch (_) {
        if (mounted) {
          toast(context, '서버 접수는 완료됐어요. 기기 임시 자료 정리는 홈에서 다시 시도해 주세요.');
        }
      }
      // The upload session carries no trip times or distance; read the saved record once for the summary and go on without it if that fails.
      RentalRecord? summary;
      try {
        summary = await state.api
            .record(session!.record.id)
            .timeout(const Duration(seconds: 8));
      } catch (_) {}
      if (!mounted) return;
      state.refreshBookings().catchError((Object _) {});
      setState(() => leaving = true);
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(
          builder: (_) => ReceiptScreen(
            receipt: receipt,
            usesTestVin: session!.record.usesTestVin,
            isDemo: session!.record.isDemo,
            record: summary,
          ),
        ),
      );
    });
  }

  // Await a committed draft before leaving the form so a quick back action does not discard the latest typed checks or note.
  Future<void> confirmLeave() async {
    if (busy) return;
    try {
      await persistDraft();
    } catch (_) {
      if (mounted) {
        setState(() => error = "기기에 저장하지 못했어요. 저장 공간을 확인하고 다시 시도해 주세요.");
      }
      return;
    }
    if (!mounted) return;
    final yes = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('사진 화면에서 나갈까요?'),
        content: const Text(
          '사진과 확인 내역은 이 계정의 기기에 임시 보관했어요. 홈의 작성 중인 사진에서 이어갈 수 있어요. 임시 자료는 7일 뒤 만료돼요.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('계속 작성'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('나가기'),
          ),
        ],
      ),
    );
    if (yes == true && mounted) {
      setState(() => leaving = true);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) Navigator.pop(context);
      });
    }
  }

  // Present category-specific capture advice, server photos and an unsent preview while keeping manual review separate from uploads.
  Widget categoryPanel(EvidenceCategory category, bool enabled) {
    final photos = session!.photosFor(category), draft = drafts[category];
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  category.label,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              Chip(label: Text('${photos.length}/5장 저장')),
            ],
          ),
          const SizedBox(height: 6),
          Text(category.description),
          PhotoGuidance(category),
          if (session!.isReturn)
            TextButton.icon(
              onPressed: () => showPhotoComparison(
                context,
                category,
                session!.pickupPhotos
                    .where((p) => p.category == category)
                    .toList(),
                session!.photosFor(category),
                drafts[category]?.bytes,
              ),
              icon: const Icon(Icons.compare_outlined),
              label: const Text('인수·반납 사진 비교'),
            ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              for (final p in photos)
                SecurePhoto(
                  key: ValueKey(p.id),
                  id: p.id,
                  label: '${category.label} 전송 사진',
                  onDelete: !enabled
                      ? null
                      : () => run(() async {
                          await RentalScope.of(
                            context,
                          ).api.deletePhoto(token, p.id);
                          if (mounted) await load();
                        }),
                ),
            ],
          ),
          if (photos.length < 5)
            Wrap(
              spacing: 10,
              children: [
                OutlinedButton.icon(
                  onPressed: enabled
                      ? () => pick(category, ImageSource.camera)
                      : null,
                  icon: const Icon(Icons.photo_camera_outlined),
                  label: const Text('촬영'),
                ),
                OutlinedButton.icon(
                  onPressed: enabled
                      ? () => pick(category, ImageSource.gallery)
                      : null,
                  icon: const Icon(Icons.photo_library_outlined),
                  label: const Text('사진 선택'),
                ),
              ],
            ),
          if (draft != null) ...[
            const SizedBox(height: 12),
            ClipRRect(
              borderRadius: BorderRadius.circular(16),
              child: Image.memory(draft.bytes, height: 180, fit: BoxFit.cover),
            ),
            const SizedBox(height: 8),
            const Text('전송 전 미리보기 · 아직 서버에 저장되지 않았어요'),
            const SizedBox(height: 12),
            PrimaryAction(
              '이 사진 전송',
              busy: busy,
              onPressed:
                  enabled && !offline && !session!.expired && photos.length < 5
                  ? () => send(category)
                  : null,
            ),
            TextButton(
              onPressed: busy
                  ? null
                  : () => setState(() {
                      drafts.remove(category);
                      queueDraft();
                    }),
              child: const Text('선택 취소'),
            ),
          ],
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            value: checked[category],
            title: Text(category.checkLabel),
            onChanged: enabled
                ? (v) => setState(() {
                    checked[category] = v!;
                    dirty = true;
                    queueDraft();
                  })
                : null,
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    // Keep evidence editing and submission tied to the current server phase while displaying any emulator VIN provenance.
    final s = session, enabled = s != null && !busy;
    final ready =
        s?.ready == true && !dirty && drafts.isEmpty && !busy && !offline;
    return PopScope(
      canPop: leaving || (!busy && drafts.isEmpty && !dirty),
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) confirmLeave();
      },
      child: AppPage(
        title: s?.isReturn == true ? '반납 사진·확인' : '인수 사진·확인',
        children: [
          const Intro(
            '직접 확인하고 기록해요',
            '창문·소지품·외관을 사진으로 남겨 주세요. 반납 시에는 항목마다 사진 1장 이상과 직접 확인이 필요해요.',
          ),
          if (busy) const LinearProgressIndicator(),
          if (error != null) Notice(error!, isError: true),
          if (s == null && !busy)
            PrimaryAction(
              '다시 불러오기',
              onPressed: () => run(() => load(syncChecks: true)),
            ),
          if (s != null) ...[
            if (s.record.isDemo) Notice(s.record.demoNotice),
            if (s.isReturn)
              UsageMemoryNotice(
                s.record.usageMemory,
                collected: collected,
                onCollected: enabled
                    ? (space, value) => setState(() {
                        if (value) {
                          collected.add(space);
                        } else {
                          collected.remove(space);
                        }
                        dirty = true;
                        queueDraft();
                      })
                    : null,
              ),
            if (s.isReturn) BatteryReturnNotice(s.record.batteryReturn),
            if (restored)
              const Notice('기기에 남겨 둔 사진·확인을 복원했어요. 전송 전 내용을 확인해 주세요.'),
            if (localMessage != null) Notice(localMessage!),
            if (offline)
              const Notice(
                '통신이 끊겨 기기 임시 자료를 보고 있어요. 연결을 복구해 다시 불러온 뒤 서버 전송·최종 제출을 진행해 주세요.',
              ),
            OutlinedButton.icon(
              onPressed: busy
                  ? null
                  : () => run(() async {
                      await persistDraft();
                      await load();
                    }),
              icon: const Icon(Icons.sync),
              label: const Text('전송 상태 다시 확인'),
            ),
            OutlinedButton(
              onPressed: busy ? null : () => run(persistDraft),
              child: const Text('기기 임시 저장'),
            ),
            Notice(
              s.expired
                  ? '사진 전송 시간이 지났어요. 전송 연결을 갱신해 주세요.'
                  : 'VIN 끝 6자리 ${s.record.vinSuffix} · ${s.isReturn ? '반납' : '인수'} 사진\n전송 가능 시간 ${dateLabel(s.expiresAt)}까지',
            ),
            if (s.expired || error != null)
              OutlinedButton(
                onPressed: busy ? null : renew,
                child: const Text('전송 연결 새로 받기'),
              ),
            const Text('JPEG·PNG·WebP · 한 장 8MB 이하 · 항목별 최대 5장'),
            for (final category in EvidenceCategory.values)
              categoryPanel(category, enabled),
            TextField(
              controller: note,
              maxLength: 500,
              maxLines: 3,
              enabled: enabled,
              decoration: const InputDecoration(labelText: '추가 메모 (선택)'),
              onChanged: (_) => setState(() {
                dirty = true;
                queueDraft();
              }),
            ),
            PrimaryAction(
              '직접 확인·메모 저장',
              busy: busy,
              onPressed: enabled ? save : null,
            ),
            if (dirty) const Notice('변경한 직접 확인·메모를 저장해 주세요.'),
            if (s.isReturn) ...[
              PrimaryAction(
                '반납 점검 최종 제출',
                busy: busy,
                onPressed: ready ? submit : null,
                icon: Icons.check_circle_outline,
              ),
              if (!ready && !busy)
                const Text(
                  '세 항목의 사진과 직접 확인을 모두 저장하고, 전송 전 사진을 정리하면 제출할 수 있어요.',
                ),
            ] else
              const Notice(
                '인수 사진은 선택 사항이에요. 반납 점검은 차량 화면에서 반납 관측값을 저장한 뒤 진행해 주세요.',
              ),
            const Notice('점검 기록 제출은 카셰어링 업체의 실제 반납·요금 정산과 별도예요.'),
          ],
        ],
      ),
    );
  }
}
