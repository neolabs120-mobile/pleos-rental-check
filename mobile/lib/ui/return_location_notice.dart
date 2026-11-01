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
