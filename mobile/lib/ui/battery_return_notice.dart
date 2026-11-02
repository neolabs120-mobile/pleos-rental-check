import 'package:flutter/material.dart';
import '../models/rental_models.dart';
import 'components.dart';

class BatteryReturnNotice extends StatelessWidget {
  // Show the immutable pickup target and saved return reading; the phone does not query the vehicle or promise a live charge value.
  const BatteryReturnNotice(this.value, {super.key});
  final Json value;

  @override
  // A missing SDK reading remains visibly unknown and never becomes a zero-percent target or a billing approval.
  Widget build(BuildContext context) {
    final target = value['targetPercent'] as num?,
        returned = value['returnPercent'] as num?,
        deficit = value['shortfallPercent'] as num?;
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('배터리 반납 기준', style: Theme.of(context).textTheme.titleMedium),
          Text(
            '인수 때 ${target == null ? '확인 불가' : '$target%'} · 반납 점검 저장 때 ${returned == null ? '확인 불가' : '$returned%'}',
          ),
          Text(
            target == null
                ? '인수 잔량을 받지 못해 자동 기준을 정할 수 없어요.'
                : returned == null
                ? '반납 때 잔량을 확인하지 못했어요.'
                : deficit != null && deficit > 0
                ? '인수 기준보다 $deficit%p 부족한 기록이에요. 충전·반납 조건은 예약한 곳에 확인해 주세요.'
                : '저장된 반납 잔량은 인수 기준 이상이에요.',
          ),
          const Text('저장된 차량 조회 값이며 현재 잔량이나 사업자의 반납·요금 정산 결과가 아니에요.'),
        ],
      ),
    );
  }
}
