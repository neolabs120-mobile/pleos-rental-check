import 'dart:typed_data';
import 'package:flutter/material.dart';
import '../app_scope.dart';

class SecurePhoto extends StatefulWidget {
  // Keep record thumbnails compact while allowing comparison views to show the complete photograph at a consistent size.
  const SecurePhoto({
    super.key,
    required this.id,
    required this.label,
    this.onDelete,
    this.width = 142,
    this.height = 110,
    this.fit = BoxFit.cover,
  });
  final String id, label;
  final VoidCallback? onDelete;
  final double width, height;
  final BoxFit fit;
  // Retain authenticated photo loading and retry state across ordinary parent rebuilds.
  @override
  State<SecurePhoto> createState() => _SecurePhotoState();
}

class _SecurePhotoState extends State<SecurePhoto> {
  Future<Uint8List>? pixels;
  // Fetch through the account-authorized API once dependencies are available, without exposing a public image URL.
  // Show a retryable protected image and a full-size viewer; comparison callers can preserve its entire frame.
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    pixels ??= RentalScope.of(context).api.photo(widget.id);
  }

  @override
  Widget build(BuildContext context) => SizedBox(
    width: widget.width,
    child: Column(
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(14),
          child: SizedBox(
            height: widget.height,
            width: widget.width,
            child: FutureBuilder<Uint8List>(
              future: pixels,
              builder: (context, snapshot) {
                if (snapshot.hasError) {
                  return TextButton.icon(
                    icon: const Icon(Icons.refresh),
                    label: const Text('사진 재시도'),
                    onPressed: () => setState(
                      () =>
                          pixels = RentalScope.of(context).api.photo(widget.id),
                    ),
                  );
                }
                if (!snapshot.hasData) {
                  return const Center(child: CircularProgressIndicator());
                }
                return Semantics(
                  label: widget.label,
                  image: true,
                  child: InkWell(
                    onTap: () => showDialog<void>(
                      context: context,
                      builder: (_) => Dialog(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Flexible(
                              child: InteractiveViewer(
                                child: Image.memory(
                                  snapshot.data!,
                                  semanticLabel: widget.label,
                                ),
                              ),
                            ),
                            TextButton(
                              onPressed: () => Navigator.pop(context),
                              child: const Text('닫기'),
                            ),
                          ],
                        ),
                      ),
                    ),
                    child: Image.memory(
                      snapshot.data!,
                      fit: widget.fit,
                      cacheWidth: 400,
                    ),
                  ),
                );
              },
            ),
          ),
        ),
        if (widget.onDelete != null)
          TextButton.icon(
            onPressed: widget.onDelete,
            icon: const Icon(Icons.delete_outline, size: 18),
            label: const Text('삭제'),
          ),
      ],
    ),
  );
}
