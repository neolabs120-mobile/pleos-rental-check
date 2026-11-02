import 'dart:convert';
import 'package:flutter/material.dart';

import '../models/rental_models.dart';
import 'components.dart';

class ReturnLocationNotice extends StatelessWidget {
  const ReturnLocationNotice(this.place, {super.key});
  final ReturnLocation place;

  @override
  // Show the same booking destination on the phone while keeping Pleos location access and Navigation in the vehicle app.
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text(
        '반납 장소 · ${place.name}',
        style: Theme.of(context).textTheme.titleMedium,
      ),
      const SizedBox(height: 6),
      Text(place.address),
      if (place.instructions.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(place.instructions),
        ),
      if (place.parkingSteps.isNotEmpty)
        ExpansionTile(
          tilePadding: EdgeInsets.zero,
          title: const Text('주차장 입구·층·구역 안내'),
          children: [
            for (final step in place.parkingSteps) ...[
              const SizedBox(height: 12),
              Text(
                '${place.parkingSteps.indexOf(step) + 1}. ${step['title']}',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              Text(step['detail'] as String),
              if (step['imageBase64'] is String)
                Image.memory(
                  base64Decode(step['imageBase64'] as String),
                  height: 160,
                  semanticLabel: '${step['title']} 안내 이미지',
                  errorBuilder: (_, error, trace) =>
                      const Text('안내 이미지를 열지 못했어요. 설명을 확인해 주세요.'),
                ),
            ],
          ],
        ),
      if (place.isTest)
        const Padding(
          padding: EdgeInsets.only(top: 10),
          child: Notice('에뮬레이터 시험 장소 · 실제 반납 거점이 아니에요.'),
        ),
      const SizedBox(height: 6),
      const Text('차량 화면에서 반납 장소 길 안내를 시작할 수 있어요. 지정 주차구역과 반납 조건은 직접 확인해 주세요.'),
    ],
  );
}
