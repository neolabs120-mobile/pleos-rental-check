import 'dart:typed_data';
import 'package:flutter/material.dart';
import '../models/rental_models.dart';
import 'secure_photo.dart';

class PhotoGuidance extends StatelessWidget {
  // Give inexperienced renters a framing example before opening the system camera, without inventing a real vehicle photo or damage diagnosis.
  const PhotoGuidance(this.category, {super.key});
  final EvidenceCategory category;

  @override
  // Use an explicitly illustrative frame and concrete capture advice; preview review remains the user's decision.
  Widget build(BuildContext context) => ExpansionTile(
    tilePadding: EdgeInsets.zero,
    leading: const Icon(Icons.crop_free),
    title: const Text('촬영 구도 보기'),
    children: [
      AspectRatio(
        aspectRatio: 2.4,
        child: Container(
          margin: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            border: Border.all(
              color: Theme.of(context).colorScheme.primary,
              width: 2,
            ),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Icon(switch (category) {
            EvidenceCategory.windows => Icons.window_outlined,
            EvidenceCategory.belongings => Icons.airline_seat_recline_normal,
            EvidenceCategory.exterior => Icons.directions_car_outlined,
          }, size: 72),
        ),
      ),
      Text(switch (category) {
        EvidenceCategory.windows =>
          '닫힌 창문과 창틀이 함께 보이게 찍어 주세요. 한 장에 모두 담기 어려우면 여러 장을 남겨 주세요.',
        EvidenceCategory.belongings =>
          '앞·뒷좌석, 발밑과 트렁크를 살펴보고 사용한 공간이 보이게 찍어 주세요.',
        EvidenceCategory.exterior =>
          '차량이 프레임에 들어오도록 거리를 두고 찍어 주세요. 확인할 부분은 가까이서 한 장 더 남겨 주세요.',
      }),
      const Text('구도 예시 도식 · 밝기와 흔들림은 전송 전 미리보기에서 직접 확인해 주세요.'),
    ],
  );
}

// Compare photos from the same rental and category, including the unsent local preview; no automatic damage or liability inference is made.
Future<void> showPhotoComparison(
  BuildContext context,
  EvidenceCategory category,
  List<EvidencePhoto> pickup,
  List<EvidencePhoto> returned,
  Uint8List? draft,
) => showDialog<void>(
  context: context,
  builder: (context) => Dialog(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: LayoutBuilder(
        builder: (context, constraints) => SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '${category.label} · 인수와 반납',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const Text('비슷한 각도의 사진을 직접 비교해 주세요. 손상이나 책임을 자동 판정하지 않아요.'),
              Wrap(
                spacing: 12,
                runSpacing: 16,
                children: [
                  for (final phase in ['인수', '반납'])
                    SizedBox(
                      width: ((constraints.maxWidth - 12) / 2).clamp(
                        110.0,
                        300.0,
                      ),
                      child: Column(
                        children: [
                          Text(
                            phase,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          if (phase == '반납' && draft != null)
                            Image.memory(
                              draft,
                              height: 180,
                              semanticLabel: '전송 전 반납 사진',
                            ),
                          for (final photo in phase == '인수' ? pickup : returned)
                            SecurePhoto(
                              id: photo.id,
                              label: '$phase ${category.label} 사진',
                              height: 180,
                              fit: BoxFit.contain,
                              width: ((constraints.maxWidth - 12) / 2).clamp(
                                110.0,
                                300.0,
                              ),
                            ),
                          if ((phase == '인수' ? pickup : returned).isEmpty &&
                              !(phase == '반납' && draft != null))
                            Text('$phase 사진이 없어요.'),
                        ],
                      ),
                    ),
                ],
              ),
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('닫기'),
              ),
            ],
          ),
        ),
      ),
    ),
  ),
);
