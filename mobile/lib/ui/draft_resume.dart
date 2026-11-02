import 'package:flutter/material.dart';
import '../app_scope.dart';
import '../data/evidence_drafts.dart';
import '../models/rental_models.dart';
import '../screens/evidence_screen.dart';
import 'components.dart';

class DraftResume extends StatefulWidget {
  // Provide an account-bound entry point after application restart, including when the server is temporarily unreachable.
  const DraftResume({super.key});
  @override
  // Keep the pending list stable across the parent's refresh and form changes.
  State<DraftResume> createState() => _DraftResumeState();
}

class _DraftResumeState extends State<DraftResume> {
  Future<List<Json>>? pending;

  @override
  // Read only the signed-in account's encrypted namespace; the home screen never guesses an upload token or borrows another reservation.
  void didChangeDependencies() {
    super.didChangeDependencies();
    pending ??= read();
  }

  // Ignore entries already submitted according to a fresh booking list and expire old drafts through the storage's normal recovery path.
  Future<List<Json>> read() async {
    final state = RentalScope.of(context), values = <Json>[];
    final store = EvidenceDrafts(
      state.store,
      state.api.base.origin,
      state.user!.id,
    );
    for (final id in await store.list()) {
      final value = await store.read(id);
      if (value != null &&
          !state.bookings.any(
            (b) => b.rentalId == value['session']['id'] && b.isSubmitted,
          )) {
        values.add({...value, 'key': id});
      }
    }
    return values;
  }

  @override
  // Refresh after returning from an evidence page so a newly saved or submitted draft is reflected on the home screen.
  void didUpdateWidget(covariant DraftResume oldWidget) {
    super.didUpdateWidget(oldWidget);
    pending = read();
  }

  // Let the renter remove only local pending data deliberately; completed server photos and receipts remain unchanged.
  Future<void> discard(Json row) async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('기기 임시 자료를 삭제할까요?'),
        content: const Text(
          '이 기기에만 있는 전송 전 사진·확인은 복구할 수 없어요. 서버에 저장한 자료는 유지돼요.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('유지'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('임시 자료 삭제'),
          ),
        ],
      ),
    );
    if (yes != true || !mounted) return;
    final state = RentalScope.of(context);
    try {
      await EvidenceDrafts(
        state.store,
        state.api.base.origin,
        state.user!.id,
      ).remove(row['key'] as String);
      if (mounted) setState(() => pending = read());
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('임시 자료를 정리하지 못했어요. 저장 공간을 확인해 주세요.')),
        );
      }
    }
  }

  @override
  // Resume unsent local work explicitly; the restored screen must reconcile online before it can report a final receipt.
  Widget build(BuildContext context) => FutureBuilder<List<Json>>(
    future: pending,
    builder: (context, snapshot) {
      if (snapshot.hasError) {
        return const Notice(
          '기기 임시 자료를 읽지 못했어요. 저장 공간을 확인해 주세요.',
          isError: true,
        );
      }
      final rows = snapshot.data ?? [];
      if (rows.isEmpty) return const SizedBox.shrink();
      return Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('작성 중인 사진·확인', style: Theme.of(context).textTheme.titleLarge),
            const Text('이 기기에 암호화해 보관한 자료예요. 서버 접수 전이며 7일 뒤 만료돼요.'),
            for (final row in rows)
              OutlinedButton.icon(
                icon: const Icon(Icons.restore),
                label: Text(
                  'VIN ···${row['session']['vinSuffix']} · ${row['session']['phase'] == 'return' ? '반납' : '인수'} 이어 쓰기',
                ),
                onPressed: () async {
                  await openPage(
                    context,
                    EvidenceScreen(token: row['token'] as String),
                  );
                  if (mounted) setState(() => pending = read());
                },
              ),
            for (final row in rows)
              TextButton.icon(
                onPressed: () => discard(row),
                icon: const Icon(Icons.delete_outline),
                label: Text(
                  'VIN ···${row['session']['vinSuffix']} 기기 임시 자료 삭제',
                ),
              ),
          ],
        ),
      );
    },
  );
}
