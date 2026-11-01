import 'package:flutter/material.dart';
import '../data/rental_api.dart';

mixin ActionState<T extends StatefulWidget> on State<T> {
  bool busy = false;
  String? error;
  Future<void> run(Future<void> Function() task) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await task();
    } catch (e) {
      if (mounted) {
        setState(
          () =>
              error = switch (e) {
                ApiException() => e.message,
                FormatException() => e.message,
                _ => '작업을 완료하지 못했어요. 다시 시도해 주세요.',
              },
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }
}

class AppPage extends StatelessWidget {
  const AppPage({
    super.key,
    required this.title,
    required this.children,
    this.actions,
    this.bottom,
    this.onRefresh,
  });
  final String title;
  final List<Widget> children;
  final List<Widget>? actions;
  final Widget? bottom;
  final Future<void> Function()? onRefresh;
  @override
  Widget build(BuildContext context) {
    Widget body = ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(24),
      children: [
        Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 600),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final child in children)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 18),
                    child: child,
                  ),
              ],
            ),
          ),
        ),
      ],
    );
    if (onRefresh != null) {
      body = RefreshIndicator(onRefresh: onRefresh!, child: body);
    }
    return Scaffold(
      appBar: AppBar(title: Text(title), actions: actions),
      body: SafeArea(child: body),
      bottomNavigationBar:
          bottom == null
              ? null
              : SafeArea(
                minimum: const EdgeInsets.fromLTRB(24, 12, 24, 16),
                child: bottom!,
              ),
    );
  }
}

class Panel extends StatelessWidget {
  const Panel({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Card(
    margin: EdgeInsets.zero,
    child: Padding(padding: const EdgeInsets.all(20), child: child),
  );
}

class Intro extends StatelessWidget {
  const Intro(this.title, this.subtitle, {super.key});
  final String title, subtitle;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        title,
        style: Theme.of(context).textTheme.headlineMedium?.copyWith(
          fontWeight: FontWeight.w800,
          height: 1.3,
        ),
      ),
      const SizedBox(height: 12),
      Text(
        subtitle,
        style: Theme.of(context).textTheme.bodyLarge?.copyWith(height: 1.6),
      ),
    ],
  );
}

class Notice extends StatelessWidget {
  const Notice(this.text, {super.key, this.isError = false});
  final String text;
  final bool isError;
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      liveRegion: true,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: isError ? colors.errorContainer : colors.secondaryContainer,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              isError ? Icons.error_outline : Icons.info_outline,
              color:
                  isError
                      ? colors.onErrorContainer
                      : colors.onSecondaryContainer,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                text,
                style: TextStyle(
                  height: 1.5,
                  color:
                      isError
                          ? colors.onErrorContainer
                          : colors.onSecondaryContainer,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class PrimaryAction extends StatelessWidget {
  const PrimaryAction(
    this.label, {
    super.key,
    this.onPressed,
    this.busy = false,
    this.icon,
  });
  final String label;
  final VoidCallback? onPressed;
  final bool busy;
  final IconData? icon;
  @override
  Widget build(BuildContext context) => FilledButton(
    onPressed: busy ? null : onPressed,
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: 15),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (busy)
            const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          else if (icon != null)
            Icon(icon, size: 20),
          if (busy || icon != null) const SizedBox(width: 10),
          Flexible(child: Text(label)),
        ],
      ),
    ),
  );
}

class PasswordField extends StatefulWidget {
  const PasswordField({
    super.key,
    required this.controller,
    this.label = '비밀번호',
    this.newPassword = false,
    this.confirm,
  });
  final TextEditingController controller;
  final String label;
  final bool newPassword;
  final TextEditingController? confirm;
  @override
  State<PasswordField> createState() => _PasswordFieldState();
}

class _PasswordFieldState extends State<PasswordField> {
  bool hidden = true;
  @override
  Widget build(BuildContext context) => TextFormField(
    controller: widget.controller,
    obscureText: hidden,
    maxLength: 128,
    enableSuggestions: false,
    autocorrect: false,
    autofillHints: [
      widget.newPassword ? AutofillHints.newPassword : AutofillHints.password,
    ],
    decoration: InputDecoration(
      labelText: widget.label,
      counterText: '',
      helperText:
          widget.newPassword && widget.confirm == null
              ? '12자 이상 입력해 주세요'
              : null,
      suffixIcon: IconButton(
        tooltip: hidden ? '비밀번호 보기' : '비밀번호 숨기기',
        onPressed: () => setState(() => hidden = !hidden),
        icon: Icon(
          hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined,
        ),
      ),
    ),
    validator: (value) {
      if (value == null || value.isEmpty) return '비밀번호를 입력해 주세요.';
      if (widget.newPassword && value.length < 12) return '12자 이상 입력해 주세요.';
      if (widget.confirm != null && value != widget.confirm!.text) {
        return '비밀번호가 일치하지 않아요.';
      }
      return null;
    },
  );
}

Widget emailField(TextEditingController controller) => TextFormField(
  controller: controller,
  keyboardType: TextInputType.emailAddress,
  maxLength: 254,
  autofillHints: const [AutofillHints.email],
  autocorrect: false,
  decoration: const InputDecoration(labelText: '이메일', counterText: ''),
  validator:
      (v) =>
          v != null && RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$').hasMatch(v.trim())
              ? null
              : '올바른 이메일을 입력해 주세요.',
);

String dateLabel(DateTime? value) {
  if (value == null) return '기록 없음';
  final d = value.toLocal();
  String two(int n) => n.toString().padLeft(2, '0');
  return '${d.year}.${two(d.month)}.${two(d.day)} ${two(d.hour)}:${two(d.minute)}';
}

String amount(double? value, String unit) =>
    value == null ? '미수신' : '${value.toStringAsFixed(1)}$unit';

void toast(BuildContext context, String text) =>
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(text)));

Future<T?> openPage<T>(BuildContext context, Widget page) =>
    Navigator.of(context).push<T>(MaterialPageRoute(builder: (_) => page));
