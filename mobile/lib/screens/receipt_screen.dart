import 'package:flutter/material.dart';
import '../models/rental_models.dart';
import '../ui/components.dart';

class ReceiptScreen extends StatelessWidget {
  // Carry the inspected record's test provenance into the final server receipt screen.
  const ReceiptScreen({
    super.key,
    required this.receipt,
    this.usesTestVin = false,
  });
  final Json receipt;
  final bool usesTestVin;
  @override
  // Keep emulator submissions visibly distinct from a real vehicle VIN observation after successful submission.
  Widget build(BuildContext context) => AppPage(
    title: '제출 결과',
    children: [
      Icon(
        Icons.check_circle_rounded,
        size: 92,
        color: Theme.of(context).colorScheme.primary,
      ),
      const Intro('점검 기록을 제출했어요', '서버에 접수된 기록은 내 점검 기록에서 다시 확인할 수 있어요.'),
      if (usesTestVin)
        const Notice('에뮬레이터 테스트 VIN으로 진행한 시험 접수예요. 실제 차량 VIN이 아니에요.'),
      Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('접수 번호'),
            const SizedBox(height: 10),
            SelectableText(receipt['receiptId'] as String),
            const SizedBox(height: 20),
            Text('접수 시각 ${dateLabel(millis(receipt['submittedAtMillis']))}'),
          ],
        ),
      ),
      const Notice('렌터카 업체의 실제 반납 처리·추가 요금 정산은 별도로 진행돼요.'),
      PrimaryAction(
        '내 대여로 돌아가기',
        onPressed: () => Navigator.of(context).popUntil((r) => r.isFirst),
      ),
    ],
  );
}
