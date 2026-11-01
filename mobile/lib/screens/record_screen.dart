import 'package:flutter/material.dart';

import '../app_scope.dart';
import '../models/rental_models.dart';
import '../ui/components.dart';
import '../ui/usage_memory_notice.dart';
import '../ui/secure_photo.dart';
import '../ui/return_location_notice.dart';
import 'evidence_screen.dart';

class RecordScreen extends StatefulWidget {
  const RecordScreen({super.key, required this.booking});
  final Booking booking;
  @override
  State<RecordScreen> createState() => _RecordScreenState();
}

class _RecordScreenState extends State<RecordScreen> with ActionState {
  RentalRecord? record;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) run(load);
    });
  }

  Future<void> load() async {
    final r = await RentalScope.of(context).api
        .record(widget.booking.rentalId!);
    if (mounted) setState(() => record = r);
  }

  Future<void> evidence() => run(() async {
    final token = await RentalScope.of(context).beginEvidence(record!);
    if (!mounted) return;
    await openPage(context, EvidenceScreen(token: token));
    if (mounted) await load();
  });
  Widget stat(String title, String value) => Expanded(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title),
        const SizedBox(height: 8),
        Text(
          value,
          style: Theme.of(context).textTheme.titleLarge
              ?.copyWith(fontWeight: FontWeight.bold),
        ),
      ],
    ),
  );
  @override
  Widget build(BuildContext context) {
    // Show the owner's saved evidence with its VIN provenance so an emulator trial remains identifiable when reopened.
    final r = record;
    return AppPage(
      title: '내 점검 기록',
      onRefresh: () => run(load),
      children: [
        Intro(
          widget.booking.vehicleName,
          '예약 ${widget.booking.number} · VIN 끝 6자리 ${widget.booking.vinSuffix}',
        ),
        if (busy) const LinearProgressIndicator(),
        if (error != null) ...[
          Notice(error!, isError: true),
          TextButton(
            onPressed: busy ? null : () => run(load),
            child: const Text('다시 불러오기'),
          ),
        ],
        if (r != null) ...[
          if (widget.booking.returnLocation != null)
            Panel(child: ReturnLocationNotice(widget.booking.returnLocation!)),
          if (r.isDemo) Notice(r.demoNotice),
          if (r.returnAt != null) UsageMemoryNotice(r.usageMemory),
          if (r.isSubmitted)
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.check_circle, size: 44),
                  const SizedBox(height: 12),
                  Text(
                    '점검 기록 제출 완료',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 10),
                  Text('접수 시각 ${dateLabel(r.submittedAt)}'),
                  const SizedBox(height: 10),
                  const Text('접수 번호'),
                  SelectableText(r.receiptId!),
                ],
              ),
            ),
          Panel(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  '차량에서 수집한 기록',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 18),
                Text('인수 ${dateLabel(r.pickupAt)}'),
                const SizedBox(height: 10),
                Text('반납 ${dateLabel(r.returnAt)}'),
                const Divider(height: 32),
                Row(
                  children: [
                    stat('주행 거리', amount(r.distanceKm, ' km')),
                    stat(
                      '연료 잔량',
                      '${amount(r.pickupFuel, '%')} → ${amount(r.returnFuel, '%')}',
                    ),
                  ],
                ),
              ],
            ),
          ),
          if (!r.isSubmitted)
            PrimaryAction(
              r.returnAt == null ? '인수 사진 남기기' : '반납 사진·확인 이어가기',
              busy: busy,
              icon: Icons.photo_camera_outlined,
              onPressed: evidence,
            ),
          for (final phase in ['pickup', 'return'])
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    phase == 'pickup' ? '인수 사진·직접 확인' : '반납 사진·직접 확인',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 16),
                  for (final category in EvidenceCategory.values) ...[
                    Row(
                      children: [
                        Icon(
                          r.checksFor(phase).checked(category)
                              ? Icons.check_circle_outline
                              : Icons.radio_button_unchecked,
                          size: 20,
                        ),
                        const SizedBox(width: 8),
                        Expanded(child: Text(category.checkLabel)),
                      ],
                    ),
                    const SizedBox(height: 10),
                    Wrap(
                      spacing: 10,
                      runSpacing: 10,
                      children: [
                        for (final photo in r.photos.where(
                          (p) => p.phase == phase && p.category == category,
                        ))
                          SecurePhoto(
                            key: ValueKey(photo.id),
                            id: photo.id,
                            label: '${category.label} 기록 사진',
                          ),
                      ],
                    ),
                    const SizedBox(height: 12),
                  ],
                  if (r.checksFor(phase).note.isNotEmpty)
                    Text('메모: ${r.checksFor(phase).note}'),
                ],
              ),
            ),
          const Notice('점검 기록 접수는 렌터카 업체의 실제 반납 완료·요금 정산을 대신하지 않아요.'),
        ],
      ],
    );
  }
}
