import 'package:flutter/material.dart';
import '../ui/components.dart';

class PolicyScreen extends StatelessWidget {
  const PolicyScreen({super.key, required this.privacy});
  final bool privacy;
  @override
  // Describe verified-email reservation discovery consistently with the current flow while retaining the draft service limitations.
  Widget build(BuildContext context) => AppPage(
    title: privacy ? '개인정보 안내' : '이용약관',
    children: [
      const Notice('공모전 시연용 초안 · competition-draft-2026-10'),
      Text(
        privacy
            ? '이메일·닉네임·비밀번호 해시·동의 이력·계정 세션을 계정 운영에 사용해요. 예약 이메일·차량 식별 정보로 대여 소유권을 확인해요. 이용 건별 차량 관측값·사진·직접 확인·메모·접수 번호를 서버에 보관해요. 사진은 서버에서 재인코딩해 EXIF·GPS 메타데이터를 제거해요. 사진 내용 자체에 개인정보가 있을 수 있으니 제출 전 확인해 주세요.\n\n운영 주체·문의처·보관 기간·탈퇴 및 삭제 절차는 실제 서비스 공개 전에 확정해야 해요. 현재 초안에는 외부 렌터카 업체로 전송하거나 결제하는 기능이 없어요.'
            : '렌트체크는 렌터카 이용자가 인수·반납 관측 기록과 직접 확인한 사진을 보관하는 공모전 시연 서비스예요. 가입 후 이메일을 확인하고 로그인하면 예약에 사용한 이메일로 발급된 내 대여 건이 표시돼요. 차량 QR을 읽고 예약한 차량의 연결을 승인해요.\n\n이메일 확인은 운전면허 확인이나 법적 본인인증이 아니에요. 관측값·사진은 자동 손상 판정, 배상 책임 판정, 실제 반납 처리 또는 요금 정산을 대신하지 않아요. 사용자가 직접 확인하고 제출한 내용을 기록해요. 실제 서비스 전 운영 주체와 이용·탈퇴·삭제 절차를 확정해야 해요.',
        style: Theme.of(context).textTheme.bodyLarge?.copyWith(height: 1.8),
      ),
    ],
  );
}
