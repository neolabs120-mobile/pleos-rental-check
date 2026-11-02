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
    final theme = Theme.of(context), colors = theme.colorScheme;
    final target = value['targetPercent'] as num?,
        returned = value['returnPercent'] as num?,
        deficit = value['shortfallPercent'] as num?;
    final short = deficit != null && deficit > 0;
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text('배터리 반납 기준', style: theme.textTheme.titleMedium),
              ),
              if (short)
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: colors.errorContainer,
                    borderRadius: BorderRadius.circular(99),
                  ),
                  child: Text(
                    '$deficit%p 부족',
                    style: theme.textTheme.labelMedium?.copyWith(
                      color: colors.onErrorContainer,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              _Figure('인수 때', target == null ? '확인 불가' : '$target%'),
              const SizedBox(width: 28),
              _Figure(
                '반납 점검 저장 때',
                returned == null ? '확인 불가' : '$returned%',
                color: short ? colors.onErrorContainer : colors.primary,
              ),
            ],
          ),
          // The bar fills to the saved return value and a tick marks the pickup reference; it is not a range estimate.
          if (target != null && returned != null)
            Padding(
              padding: const EdgeInsets.only(top: 14),
              child: LayoutBuilder(
                builder: (context, box) => SizedBox(
                  height: 10,
                  child: Stack(
                    children: [
                      Container(
                        decoration: BoxDecoration(
                          color: colors.surfaceContainer,
                          borderRadius: BorderRadius.circular(99),
                        ),
                      ),
                      Container(
                        width: box.maxWidth * (returned / 100).clamp(0, 1),
                        decoration: BoxDecoration(
                          color: short
                              ? colors.onErrorContainer
                              : colors.primary,
                          borderRadius: BorderRadius.circular(99),
                        ),
                      ),
                      Positioned(
                        left: box.maxWidth * (target / 100).clamp(0, 1) - 1.5,
                        top: 0,
                        bottom: 0,
                        child: Container(width: 3, color: colors.onSurface),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          const SizedBox(height: 14),
          Text(
            target == null
                ? '인수 잔량을 받지 못해 자동 기준을 정할 수 없어요.'
                : returned == null
                ? '반납 때 잔량을 확인하지 못했어요.'
                : short
                ? '인수 기준보다 $deficit%p 부족한 기록이에요. 충전·반납 조건은 예약한 곳에 확인해 주세요.'
                : '저장된 반납 잔량은 인수 기준 이상이에요.',
          ),
          const SizedBox(height: 6),
          Text(
            '저장된 차량 조회 값이며 현재 잔량이나 사업자의 반납·요금 정산 결과가 아니에요.',
            style: theme.textTheme.bodySmall?.copyWith(
              color: colors.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}

// Present one saved battery value as a small caption over a large figure.
class _Figure extends StatelessWidget {
  const _Figure(this.label, this.value, {this.color});
  final String label, value;
  final Color? color;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        label,
        style: Theme.of(context).textTheme.bodySmall?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
      Text(
        value,
        style: Theme.of(context).textTheme.headlineSmall?.copyWith(
          fontWeight: FontWeight.w800,
          color: color,
        ),
      ),
    ],
  );
}
