typedef Json = Map<String, dynamic>;

class AccountUser {
  const AccountUser({
    required this.id,
    required this.email,
    required this.nickname,
  });
  factory AccountUser.fromJson(Json j) => AccountUser(
    id: j['id'] as String,
    email: j['email'] as String,
    nickname: j['nickname'] as String,
  );
  final String id, email, nickname;
}

class Booking {
  Booking.fromJson(Json j)
    : id = j['id'] as String,
      number = j['bookingNumber'] as String,
      vehicleName = j['vehicleName'] as String,
      vinSuffix = j['vinSuffix'] as String,
      isDemo = j['isDemo'] == true,
      rentalId = j['rentalId'] as String?,
      submittedAt = millis(j['submittedAtMillis']);
  final String id, number, vehicleName, vinSuffix;
  final bool isDemo;
  final String? rentalId;
  final DateTime? submittedAt;
  bool get isSubmitted => submittedAt != null;
}

class EmailChallenge {
  EmailChallenge.fromJson(Json j, {required this.email, required this.isReset})
    : id = j['challengeId'] as String,
      expiresAt = millis(j['expiresAtMillis'])!,
      fileDelivery = j['deliveryMode'] == 'file',
      resendAt = DateTime.now().add(const Duration(seconds: 60));
  final String id, email;
  final bool isReset, fileDelivery;
  final DateTime expiresAt, resendAt;
}

enum EvidenceCategory {
  windows('창문', '모든 창문을 닫은 상태를 촬영해 주세요.', '모든 창문을 닫았어요'),
  belongings('소지품', '좌석·수납공간에 물건이 없는지 확인해 주세요.', '두고 내린 소지품이 없어요'),
  exterior('외관', '차량 외관과 확인이 필요한 부분을 촬영해 주세요.', '차량 외관 상태를 확인했어요');

  const EvidenceCategory(this.label, this.description, this.checkLabel);
  final String label, description, checkLabel;
}

class ManualChecks {
  const ManualChecks({
    this.windows = false,
    this.belongings = false,
    this.exterior = false,
    this.note = '',
  });
  factory ManualChecks.fromJson(Json j) => ManualChecks(
    windows: j['windows'] == true,
    belongings: j['belongings'] == true,
    exterior: j['exterior'] == true,
    note: j['note'] as String? ?? '',
  );
  final bool windows, belongings, exterior;
  final String note;
  bool get complete => windows && belongings && exterior;
  bool checked(EvidenceCategory c) => switch (c) {
    EvidenceCategory.windows => windows,
    EvidenceCategory.belongings => belongings,
    EvidenceCategory.exterior => exterior,
  };
  Json toJson() => {
    'windows': windows,
    'belongings': belongings,
    'exterior': exterior,
    'note': note,
  };
}

class EvidencePhoto {
  EvidencePhoto.fromJson(Json j)
    : id = j['id'] as String,
      phase = j['phase'] as String,
      category = EvidenceCategory.values.byName(j['category'] as String);
  final String id, phase;
  final EvidenceCategory category;
}

class UsageMemory {
  // Decode only the five supported locations; older server records remain an explicitly incomplete observation history.
  UsageMemory.fromJson(Json? j)
    : opened = _spaces(j?['openedSpaces']),
      marked = _spaces(j?['markedSpaces']),
      monitoringGaps = j?['monitoringGaps'] != false;
  final List<String> opened, marked;
  final bool monitoringGaps;
  static const labels = <String, String>{
    'FRONT_LEFT': '운전석 주변',
    'FRONT_RIGHT': '앞쪽 오른쪽 좌석 주변',
    'REAR_LEFT': '뒤쪽 왼쪽 좌석 주변',
    'REAR_RIGHT': '뒤쪽 오른쪽 좌석 주변',
    'TRUNK': '트렁크',
  };
  // A location is an observation or user mark, never proof that belongings are present or absent.
  static List<String> _spaces(dynamic value) => value is List
      ? value.whereType<String>().where(labels.containsKey).toSet().toList()
      : [];
}

class RentalRecord {
  // Decode saved observations together with their test provenance, defaulting older server responses to a real VIN record.
  RentalRecord.fromJson(Json j)
    : id = j['id'] as String,
      revision = (j['revision'] as num).toInt(),
      vinSuffix = j['vinSuffix'] as String,
      isDemo = j['isDemo'] == true,
      usesTestVin = j['usesTestVin'] == true,
      usageMemory = UsageMemory.fromJson(j['usageMemory'] as Json?),
      receiptId = j['receiptId'] as String?,
      submittedAt = millis(j['submittedAtMillis']),
      pickupAt = millis(j['pickupAtMillis']),
      returnAt = millis(j['returnAtMillis']),
      distanceKm = (j['distanceKm'] as num?)?.toDouble(),
      pickupFuel = (j['pickupFuel'] as num?)?.toDouble(),
      returnFuel = (j['returnFuel'] as num?)?.toDouble(),
      photos = (j['photos'] as List)
          .map((p) => EvidencePhoto.fromJson(p as Json))
          .toList(),
      checks = j['checks'] as Json;
  final String id, vinSuffix;
  final int revision;
  final bool isDemo, usesTestVin;
  final UsageMemory usageMemory;
  final String? receiptId;
  final DateTime? submittedAt, pickupAt, returnAt;
  final double? distanceKm, pickupFuel, returnFuel;
  final List<EvidencePhoto> photos;
  final Json checks;
  bool get isSubmitted => submittedAt != null && receiptId != null;
  // Distinguish the substituted emulator identity from a fully synthetic demo so real SDK readings remain clearly described.
  String get demoNotice => usesTestVin
      ? '에뮬레이터 테스트 VIN · 실제 차량 VIN이 아니며 나머지 항목은 SDK 조회 값이에요.'
      : '합성 시연 데이터 · 실제 차량 관측값이 아니에요.';
  ManualChecks checksFor(String phase) =>
      ManualChecks.fromJson(checks[phase] as Json? ?? const {});
}

class UploadSession {
  UploadSession.fromJson(Json j)
    : record = RentalRecord.fromJson(j),
      phase = j['phase'] as String,
      expiresAt = millis(j['expiresAtMillis'])!,
      checks = ManualChecks.fromJson(j['checks'] as Json);
  final RentalRecord record;
  final String phase;
  final DateTime expiresAt;
  final ManualChecks checks;
  bool get isReturn => phase == 'return';
  bool get expired => !DateTime.now().isBefore(expiresAt);
  bool get ready =>
      isReturn &&
      !expired &&
      checks.complete &&
      EvidenceCategory.values.every((c) => photosFor(c).isNotEmpty);
  List<EvidencePhoto> photosFor(EvidenceCategory c) =>
      record.photos.where((p) => p.category == c && p.phase == phase).toList();
}

DateTime? millis(dynamic v) =>
    v == null ? null : DateTime.fromMillisecondsSinceEpoch((v as num).toInt());
