import 'package:flutter/material.dart';
import '../models/rental_models.dart';
import '../ui/components.dart';

class ReceiptScreen extends StatelessWidget {
  // Carry the inspected record's test provenance into the final server receipt screen.
  const ReceiptScreen({
    super.key,
    required this.receipt,
    this.usesTestVin = false,
    this.isDemo = false,
  });
  final Json receipt;
  final bool usesTestVin;
  // Synthetic demo records must stay labelled on the receipt, not only while uploading.
  final bool isDemo;
  @override
  // Keep emulator submissions visibly distinct from a real vehicle VIN observation after successful submission.
  Widget build(BuildContext context) => AppPage(
    title: '제출 결과',
    children: [
      Align(
        alignment: Alignment.centerLeft,
        child: Container(
          width: 76,
          height: 76,
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primaryContainer,
            shape: BoxShape.circle,
          ),
          child: Icon(
            Icons.check_rounded,
            size: 44,
            color: Theme.of(context).colorScheme.primary,
          ),
        ),
      ),
      const Intro('점검 기록을 제출했어요', '서버에 접수된 기록은 내 점검 기록에서 다시 확인할 수 있어요.'),
      if (isDemo) const Notice('합성 시연 데이터로 진행한 시험 접수예요. 실제 차량 관측값이 아니에요.'),
      if (usesTestVin)
        const Notice('에뮬레이터 테스트 VIN으로 진행한 시험 접수예요. 실제 차량 VIN이 아니에요.'),
      Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '접수 번호',
              style: Theme.of(context).textTheme.labelLarge?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                fontSize: 14,
              ),
            ),
            const SizedBox(height: 8),
            // Keep the full server receipt identifier selectable, set in a tinted well so it reads as the key result.
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainer,
                borderRadius: BorderRadius.circular(12),
              ),
              child: SelectableText(
                receipt['receiptId'] as String,
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
            ),
            const SizedBox(height: 16),
            const Divider(height: 1),
            const SizedBox(height: 14),
            Row(
              children: [
                Icon(
                  Icons.schedule_rounded,
                  size: 18,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                const SizedBox(width: 8),
                Text(
                  '접수 시각 ${dateLabel(millis(receipt['submittedAtMillis']))}',
                ),
              ],
            ),
          ],
        ),
      ),
      const Notice('카셰어링 업체의 실제 반납 처리·추가 요금 정산은 별도로 진행돼요.'),
      PrimaryAction(
        '내 대여로 돌아가기',
        onPressed: () => Navigator.of(context).popUntil((r) => r.isFirst),
      ),
    ],
  );
}
