import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../app_scope.dart';
import '../ui/components.dart';

class ScanLinkScreen extends StatefulWidget {
  const ScanLinkScreen({super.key, this.linkInput = false});
  final bool linkInput;
  @override
  // Keep camera and link input in the same validated connection flow while allowing a direct camera-free entry from home.
  State<ScanLinkScreen> createState() => _ScanLinkScreenState();
}

class _ScanLinkScreenState extends State<ScanLinkScreen>
    with WidgetsBindingObserver {
  final scanner = MobileScannerController(
    autoStart: true,
    formats: const [BarcodeFormat.qrCode],
    detectionSpeed: DetectionSpeed.noDuplicates,
  );
  final input = TextEditingController();
  bool handled = false, scanning = false;
  String? error;
  @override
  // Observe foreground changes so an explicitly started camera can pause safely when the app loses focus.
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  // Stop background camera use and resume only an active scanner with permission already granted by the driver.
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (!scanning || !scanner.value.hasCameraPermission) return;
    if (state == AppLifecycleState.resumed) {
      scanner.start();
    } else {
      scanner.stop();
    }
  }

  @override
  // Release camera, lifecycle observation and pasted link contents when leaving the connection screen.
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    scanner.dispose();
    input.dispose();
    super.dispose();
  }

  // Parse and validate the configured server and token before returning a pending request; reading a link never approves a vehicle.
  Future<void> accept(String raw) async {
    if (handled) return;
    try {
      RentalScope.of(context).acceptLink(raw);
      handled = true;
      await scanner.stop();
      if (mounted) Navigator.of(context).pop(true);
    } on FormatException catch (e) {
      if (mounted) setState(() => error = e.message);
    }
  }

  @override
  // Put the paste field first for the camera-free entry, retaining the same server validation and optional camera recovery.
  Widget build(BuildContext context) => AppPage(
    title: widget.linkInput ? 'QR 링크 입력' : '차량 QR 연결',
    children: [
      Intro(
        widget.linkInput ? 'QR 링크를 붙여넣어 주세요' : '차량 화면의 QR을 읽어 주세요',
        '차량 연결 QR 또는 사진 전송 QR을 사용할 수 있어요.',
      ),
      if (!scanning && !widget.linkInput)
        PrimaryAction(
          '카메라로 QR 스캔',
          icon: Icons.qr_code_scanner,
          onPressed: () => setState(() => scanning = true),
        ),
      if (scanning)
        ClipRRect(
          borderRadius: BorderRadius.circular(24),
          child: SizedBox(
            height: 300,
            child: MobileScanner(
              controller: scanner,
              onDetect: (capture) {
                for (final b in capture.barcodes) {
                  if (b.rawValue != null) {
                    accept(b.rawValue!);
                    break;
                  }
                }
              },
              errorBuilder: (context, exception) => const Center(
                child: Padding(
                  padding: EdgeInsets.all(24),
                  child: Text('카메라 권한을 허용해 주세요. 아래에 QR 링크를 붙여넣어 연결할 수도 있어요.'),
                ),
              ),
            ),
          ),
        ),
      const Text('QR 스캔이 어렵다면 링크를 붙여넣어 주세요.'),
      TextField(
        controller: input,
        maxLines: 3,
        autocorrect: false,
        enableSuggestions: false,
        decoration: const InputDecoration(
          labelText: '차량 QR 링크',
          hintText: 'https://서버/account#pair=…',
        ),
      ),
      if (error != null) Notice(error!, isError: true),
      PrimaryAction('링크 확인', onPressed: () => accept(input.text)),
      if (!scanning && widget.linkInput)
        TextButton.icon(
          onPressed: () => setState(() => scanning = true),
          icon: const Icon(Icons.qr_code_scanner),
          label: const Text('카메라로 QR 스캔'),
        ),
      const Notice(
        '스캔한 링크가 설정된 서버와 일치해야 해요. 차량 연결은 로그인 후 대여 차량을 선택하고 승인해야 완료돼요.',
      ),
    ],
  );
}
