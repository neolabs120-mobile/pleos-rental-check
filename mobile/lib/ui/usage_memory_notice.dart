import 'package:flutter/material.dart';
import '../models/rental_models.dart';
import 'components.dart';

class UsageMemoryNotice extends StatelessWidget {
  // Present the vehicle's observed spaces beside the user's own luggage marks without replacing any required return photo or check.
  const UsageMemoryNotice(
    this.memory, {
    super.key,
    this.collected,
    this.onCollected,
  });
  final UsageMemory memory;
  final Set<String>? collected;
  final void Function(String, bool)? onCollected;

  @override
  // Make incomplete monitoring explicit so an empty history can never be read as an automatic all-clear for belongings.
  Widget build(BuildContext context) => Panel(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('소지품을 다시 확인할 곳', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 12),
        if (memory.opened.isNotEmpty)
          Text(
            '앱이 열림을 관측한 곳: ${memory.opened.map((s) => UsageMemory.labels[s]!).join(', ')}',
          ),
        if (memory.marked.isNotEmpty)
          Text(
            '내가 짐을 뒀다고 표시한 곳: ${memory.marked.map((s) => UsageMemory.labels[s]!).join(', ')}',
          ),
        if (memory.luggageMemos.isNotEmpty)
          Text(
            '${memory.luggageMemos.where((m) => (collected ?? memory.collectedSpaces.toSet()).contains(m.space)).length}/${memory.luggageMemos.length}개 챙김 확인',
          ),
        for (final memo in memory.luggageMemos)
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            title: Text('${UsageMemory.labels[memo.space]} · ${memo.item}'),
            subtitle: const Text('직접 살펴본 뒤 챙겼어요'),
            value: (collected ?? memory.collectedSpaces.toSet()).contains(
              memo.space,
            ),
            onChanged: onCollected == null
                ? null
                : (value) => onCollected!(memo.space, value!),
          ),
        if (memory.opened.isEmpty &&
            memory.marked.isEmpty &&
            memory.luggageMemos.isEmpty)
          const Text('기록된 장소가 없어요. 사용한 공간과 소지품을 직접 확인해 주세요.'),
        const SizedBox(height: 10),
        const Text(
          '문 열림과 짐 메모는 물건 감지 결과가 아니에요. 표시된 곳 외에도 직접 확인하고 소지품 사진·확인을 저장해 주세요.',
        ),
        if (memory.monitoringGaps)
          const Text('앱 화면이 꺼진 동안 등 관측하지 못한 동작이 있을 수 있어요.'),
      ],
    ),
  );
}
