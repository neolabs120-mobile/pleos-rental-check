import 'package:flutter/material.dart';
import '../models/rental_models.dart';
import 'components.dart';

// Summarize the finished rental from values the server already saved; nothing here is estimated or re-measured.
class TripSummaryCard extends StatelessWidget {
  const TripSummaryCard(this.record, {super.key});
  final RentalRecord record;

  // Whole minutes between the saved pickup and return, or null while either is missing or out of order.
  static String? durationText(DateTime? pickup, DateTime? returned) {
    if (pickup == null || returned == null || returned.isBefore(pickup)) {
      return null;
    }
    final minutes = returned.difference(pickup).inMinutes;
    final days = minutes ~/ 1440,
        hours = minutes % 1440 ~/ 60,
        rest = minutes % 60;
    return [
      if (days > 0) '$days일',
      if (hours > 0) '$hours시간',
      if (rest > 0 || minutes == 0) '$rest분',
    ].join(' ');
  }

  // Electric vehicles report battery instead of fuel; use whichever pair the record actually holds.
  static ({String label, String value, String? detail})? energy(
    RentalRecord r,
  ) {
    final target = (r.batteryReturn['targetPercent'] as num?)?.toDouble();
    final returned = (r.batteryReturn['returnPercent'] as num?)?.toDouble();
    final fuel = r.pickupFuel != null || r.returnFuel != null;
    final from = fuel ? r.pickupFuel : target,
        to = fuel ? r.returnFuel : returned;
    if (from == null || to == null) return null;
    final used = from - to;
    return (
      label: fuel ? '연료' : '배터리',
      value: '${from.toStringAsFixed(0)} → ${to.toStringAsFixed(0)}%',
      detail: used > 0
          ? '${used.toStringAsFixed(0)}%p 사용'
          : used < 0
          ? '${(-used).toStringAsFixed(0)}%p 늘어남'
          : '변화 없음',
    );
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme,
        text = Theme.of(context).textTheme;
    final duration = durationText(record.pickupAt, record.returnAt);
    final power = energy(record);
    Widget metric(
      IconData icon,
      String label,
      String value, [
      String? detail,
    ]) => Expanded(
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: scheme.surfaceContainer,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, size: 18, color: scheme.primary),
                const SizedBox(width: 6),
                Text(
                  label,
                  style: text.labelLarge?.copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            // Scale down rather than wrap so a long value keeps both cards the same height.
            FittedBox(
              fit: BoxFit.scaleDown,
              alignment: Alignment.centerLeft,
              child: Text(
                value,
                maxLines: 1,
                style: text.titleLarge?.copyWith(fontWeight: FontWeight.w700),
              ),
            ),
            if (detail != null) ...[
              const SizedBox(height: 2),
              Text(
                detail,
                style: text.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
              ),
            ],
          ],
        ),
      ),
    );
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '이번 이용 요약',
            style: text.titleMedium?.copyWith(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 4),
          Text(
            '${dateLabel(record.pickupAt)} ~ ${dateLabel(record.returnAt)}',
            style: text.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
          ),
          const SizedBox(height: 14),
          IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                metric(Icons.schedule_rounded, '이용 시간', duration ?? '기록 없음'),
                const SizedBox(width: 10),
                metric(
                  Icons.route_rounded,
                  '주행 거리',
                  record.distanceKm == null
                      ? '미수신'
                      : '${record.distanceKm!.toStringAsFixed(1)} km',
                ),
              ],
            ),
          ),
          const SizedBox(height: 10),
          IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                metric(
                  power?.label == '연료'
                      ? Icons.local_gas_station_rounded
                      : Icons.battery_charging_full_rounded,
                  power?.label ?? '배터리',
                  power?.value ?? '미수신',
                  power?.detail,
                ),
                const SizedBox(width: 10),
                metric(
                  Icons.photo_library_outlined,
                  '남긴 기록',
                  '사진 ${record.photos.length}장',
                  '서버에 저장됨',
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
