import 'package:flutter/material.dart';
import '../app_scope.dart';
import '../data/connection_link.dart';
import '../models/rental_models.dart';
import '../ui/components.dart';
import 'claim_booking_screen.dart';
import 'evidence_screen.dart';
import 'policy_screen.dart';
import 'record_screen.dart';
import 'scan_link_screen.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with ActionState {
  int tab = 0;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) run(() => RentalScope.of(context).refreshBookings());
    });
  }

  Future<void> scan() async {
    // Refresh the verified account's reservations when a new vehicle QR arrives, including bookings issued while this screen was already open; approval remains explicit.
    final previous = RentalScope.of(context).pendingLink;
    await openPage(context, const ScanLinkScreen());
    if (!mounted) return;
    final state = RentalScope.of(context);
    if (state.pendingLink != previous) setState(() => error = null);
    if (state.pendingLink?.kind == ConnectionKind.pairing) {
      await run(state.refreshBookings);
    } else if (state.pendingLink?.kind == ConnectionKind.upload) {
      await resumeUpload();
    }
  }

  Future<void> resumeUpload() => run(() async {
    final state = RentalScope.of(context),
        token = RentalScope.of(context).pendingLink!.token;
    await state.api.uploadSession(token);
    if (!mounted) return;
    state.clearLink();
    await openPage(context, EvidenceScreen(token: token));
    if (mounted) await state.refreshBookings();
  });
  Future<void> approve(Booking booking) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('이 차량에 연결할까요?'),
        content: Text(
          '${booking.vehicleName}\n예약 ${booking.number}\nVIN 끝 6자리 ${booking.vinSuffix}\n\n예약한 차량인지 확인해 주세요.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('연결 승인'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    await run(() async {
      final state = RentalScope.of(context);
      await state.api.approve(state.pendingLink!.token, booking.id);
      state.clearLink();
      if (mounted) toast(context, '차량 연결을 승인했어요. 차량 화면에서 인수 점검을 시작해 주세요.');
    });
  }

  Widget bookingCard(Booking booking, bool pairing) => Panel(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            const Icon(Icons.directions_car_outlined),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                booking.vehicleName,
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ),
            Chip(
              label: Text(
                booking.isSubmitted
                    ? '제출 완료'
                    : booking.rentalId == null
                    ? '인수 대기'
                    : '점검 진행 중',
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Text('예약 ${booking.number}'),
        const SizedBox(height: 6),
        Text('VIN 끝 6자리 ${booking.vinSuffix}'),
        if (booking.isDemo)
          const Padding(
            padding: EdgeInsets.only(top: 12),
            child: Notice('합성 시연 데이터'),
          ),
        const SizedBox(height: 18),
        if (pairing && !booking.isSubmitted)
          PrimaryAction(
            '이 차량 연결 승인',
            busy: busy,
            onPressed: () => approve(booking),
          ),
        if (booking.rentalId != null)
          OutlinedButton.icon(
            icon: const Icon(Icons.fact_check_outlined),
            label: Text(booking.isSubmitted ? '접수 결과·기록 보기' : '점검 기록·사진 전송'),
            onPressed: busy
                ? null
                : () async {
                    await openPage(context, RecordScreen(booking: booking));
                    if (mounted) {
                      await run(
                        () => RentalScope.of(context).refreshBookings(),
                      );
                    }
                  },
          )
        else
          const Text('차량 연결 후 차량 화면에서 인수 상태를 저장하면 점검 기록이 나타나요.'),
      ],
    ),
  );
  @override
  // Make the verified account's reservations the normal entry point, with manual codes available only as a secondary recovery option.
  Widget build(BuildContext context) {
    final state = RentalScope.of(context);
    final pairing = state.pendingLink?.kind == ConnectionKind.pairing;
    final rows = tab == 1
        ? state.bookings.where((b) => b.rentalId != null).toList()
        : state.bookings;
    return AppPage(
      title: ['내 대여', '내 점검 기록', '내 정보'][tab],
      actions: [
        IconButton(
          tooltip: '새로고침',
          onPressed: busy ? null : () => run(state.refreshBookings),
          icon: const Icon(Icons.refresh),
        ),
      ],
      onRefresh: () => run(state.refreshBookings),
      bottom: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (i) => setState(() => tab = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.directions_car_outlined),
            selectedIcon: Icon(Icons.directions_car),
            label: '내 대여',
          ),
          NavigationDestination(
            icon: Icon(Icons.receipt_long_outlined),
            selectedIcon: Icon(Icons.receipt_long),
            label: '기록',
          ),
          NavigationDestination(
            icon: Icon(Icons.person_outline),
            selectedIcon: Icon(Icons.person),
            label: '내 정보',
          ),
        ],
      ),
      children: [
        if (tab != 2) ...[
          Intro(
            tab == 0 ? '${state.user!.nickname}님, 안전하게 다녀오세요' : '차량과 함께한 기록',
            tab == 0
                ? '예약한 이메일의 차량이 자동으로 보여요. 차량 QR을 스캔해 연결하세요.'
                : '인수·반납 기록과 제출한 사진을 다시 확인해요.',
          ),
          if (busy) const LinearProgressIndicator(),
          if (error != null) Notice(error!, isError: true),
          if (pairing) ...[
            const Notice(
              '차량 연결 QR을 읽었어요. 아래에서 예약한 차량을 선택하고 연결을 승인해 주세요. QR은 발급 후 5분간 유효해요.',
            ),
            TextButton(
              onPressed: busy ? null : state.clearLink,
              child: const Text('연결 요청 취소'),
            ),
          ],
          if (state.pendingLink?.kind == ConnectionKind.upload)
            PrimaryAction(
              '스캔한 사진 전송 이어가기',
              busy: busy,
              onPressed: resumeUpload,
            ),
          if (rows.isEmpty && !busy)
            Panel(
              child: Column(
                children: [
                  const Icon(Icons.directions_car_outlined, size: 56),
                  const SizedBox(height: 16),
                  Text(
                    tab == 0 ? '표시할 예약이 없어요' : '아직 점검 기록이 없어요',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 10),
                  Text(
                    tab == 0
                        ? '예약에 사용한 이메일로 로그인했는지 확인하고 새로고침해 주세요. 예약이 계속 보이지 않으면 예약한 곳에 문의해 주세요.'
                        : '차량에서 인수 점검을 저장하면 기록이 나타나요.',
                  ),
                ],
              ),
            ),
          for (final booking in rows) bookingCard(booking, pairing),
          if (tab == 0) ...[
            PrimaryAction(
              '차량 QR 스캔',
              icon: Icons.qr_code_scanner,
              onPressed: busy ? null : scan,
            ),
            TextButton.icon(
              onPressed: busy
                  ? null
                  : () => openPage(context, const ClaimBookingScreen()),
              icon: const Icon(Icons.help_outline),
              label: const Padding(
                padding: EdgeInsets.all(14),
                child: Text('예약이 보이지 않나요? · 코드로 연결'),
              ),
            ),
          ],
        ] else ...[
          Intro(state.user!.nickname, state.user!.email),
          Panel(
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.settings_outlined),
                  title: const Text('서버 연결 설정'),
                  onTap: () => openPage(context, const SettingsScreen()),
                ),
                ListTile(
                  leading: const Icon(Icons.description_outlined),
                  title: const Text('이용약관'),
                  onTap: () =>
                      openPage(context, const PolicyScreen(privacy: false)),
                ),
                ListTile(
                  leading: const Icon(Icons.privacy_tip_outlined),
                  title: const Text('개인정보 안내'),
                  onTap: () =>
                      openPage(context, const PolicyScreen(privacy: true)),
                ),
              ],
            ),
          ),
          const Notice('점검 접수는 렌터카 업체의 실제 반납 처리·요금 정산과 별도로 진행돼요.'),
          if (error != null) Notice(error!, isError: true),
          PrimaryAction('로그아웃', busy: busy, onPressed: () => run(state.logout)),
        ],
      ],
    );
  }
}
