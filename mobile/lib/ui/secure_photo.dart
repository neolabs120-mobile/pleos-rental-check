import 'dart:typed_data';
import 'package:flutter/material.dart';
import '../app_scope.dart';

class SecurePhoto extends StatefulWidget {
  const SecurePhoto({
    super.key,
    required this.id,
    required this.label,
    this.onDelete,
  });
  final String id, label;
  final VoidCallback? onDelete;
  @override
  State<SecurePhoto> createState() => _SecurePhotoState();
}

class _SecurePhotoState extends State<SecurePhoto> {
  Future<Uint8List>? pixels;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    pixels ??= RentalScope.of(context).api.photo(widget.id);
  }

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 142,
    child: Column(
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(14),
          child: SizedBox(
            height: 110,
            width: 142,
            child: FutureBuilder<Uint8List>(
              future: pixels,
              builder: (context, snapshot) {
                if (snapshot.hasError) {
                  return TextButton.icon(
                    icon: const Icon(Icons.refresh),
                    label: const Text('사진 재시도'),
                    onPressed:
                        () => setState(
                          () =>
                              pixels = RentalScope.of(
                                context,
                              ).api.photo(widget.id),
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
                    onTap:
                        () => showDialog<void>(
                          context: context,
                          builder:
                              (_) => Dialog(
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
                      fit: BoxFit.cover,
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
