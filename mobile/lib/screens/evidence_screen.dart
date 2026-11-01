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
  PhotoDraft(this.bytes) : requestId = const Uuid().v4();
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
  @override
  void initState() {
    super.initState();
    ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        run(() async {
          await load(syncChecks: true);
          await recoverPhoto();
        });
      }
    });
  }

  @override
  void dispose() {
    ticker.cancel();
    note.dispose();
    super.dispose();
  }

  Future<void> load({bool syncChecks = false}) async {
    final loaded = await RentalScope.of(context).api.uploadSession(token);
    if (!mounted) return;
    final phaseChanged = session != null && session!.phase != loaded.phase;
    setState(() {
      if (phaseChanged) {
        drafts.clear();
      }
      session = loaded;
      if (syncChecks || phaseChanged) {
        for (final c in EvidenceCategory.values) {
          checked[c] = loaded.checks.checked(c);
        }
        note.text = loaded.checks.note;
        dirty = false;
      }
    });
  }

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
  }

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
      } on PlatformException {
        throw const ApiException('사진을 열지 못했어요. 기기 설정에서 카메라·사진 접근 권한을 확인해 주세요.');
      }
    },
  );
  Future<void> send(EvidenceCategory category) => run(() async {
    final draft = drafts[category]!;
    await RentalScope.of(
      context,
    ).api.upload(token, category, draft.requestId, draft.bytes);
    if (!mounted) return;
    setState(() => drafts.remove(category));
    await load();
    if (mounted) toast(context, '${category.label} 사진을 서버에 저장했어요.');
  });
  Future<void> save() => run(() async {
    await RentalScope.of(context).api.saveChecks(
      token,
      ManualChecks(
        windows: checked[EvidenceCategory.windows]!,
        belongings: checked[EvidenceCategory.belongings]!,
        exterior: checked[EvidenceCategory.exterior]!,
        note: note.text,
      ),
    );
    if (!mounted) return;
    await load(syncChecks: true);
    if (mounted) toast(context, '직접 확인과 메모를 저장했어요.');
  });
  Future<void> renew() => run(() async {
    final state = RentalScope.of(context);
    final record = await state.api.record(session!.record.id);
    final renewed = await state.beginEvidence(record);
    token = renewed;
    if (mounted) await load();
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
      state.refreshBookings().catchError((Object _) {});
      setState(() => leaving = true);
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(
          builder: (_) => ReceiptScreen(
            receipt: receipt,
            usesTestVin: session!.record.usesTestVin,
          ),
        ),
      );
    });
  }

  Future<void> confirmLeave() async {
    if (busy) return;
    final yes = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('사진 화면에서 나갈까요?'),
        content: const Text(
          '아직 전송하지 않은 사진과 저장하지 않은 확인 내역은 사라져요. 서버에 저장한 기록은 유지돼요.',
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
              onPressed: enabled && photos.length < 5
                  ? () => send(category)
                  : null,
            ),
            TextButton(
              onPressed: busy
                  ? null
                  : () => setState(() => drafts.remove(category)),
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
    final s = session, enabled = s != null && !s.expired && !busy;
    final ready = s?.ready == true && !dirty && drafts.isEmpty && !busy;
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
            if (s.isReturn) UsageMemoryNotice(s.record.usageMemory),
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
              onChanged: (_) => setState(() => dirty = true),
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
            const Notice('점검 기록 제출은 렌터카 업체의 실제 반납·요금 정산과 별도예요.'),
          ],
        ],
      ),
    );
  }
}
