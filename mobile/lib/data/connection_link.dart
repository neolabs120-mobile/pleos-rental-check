enum ConnectionKind { pairing, upload }

/// A QR supplies a limited token; it never changes the trusted API endpoint.
class ConnectionLink {
  const ConnectionLink(this.kind, this.token);
  final ConnectionKind kind;
  final String token;
  static ConnectionLink parse(String raw, Uri server) {
    final uri = Uri.tryParse(raw.trim());
    if (uri == null ||
        !uri.hasAuthority ||
        !['http', 'https'].contains(uri.scheme) ||
        uri.origin != server.origin ||
        uri.userInfo.isNotEmpty ||
        uri.query.isNotEmpty) {
      throw const FormatException('설정된 서버에서 발급한 QR·링크를 사용해 주세요.');
    }
    final values = Uri.splitQueryString(uri.fragment);
    final kind = switch ((uri.path, values.keys.toSet().join(','))) {
      ('/account', 'pair') => ConnectionKind.pairing,
      ('/upload', 'token') => ConnectionKind.upload,
      _ => throw const FormatException('차량 연결 또는 사진 전송 QR을 확인해 주세요.'),
    };
    final token = values.values.single;
    if (!RegExp(r'^[A-Za-z0-9_-]{32,128}$').hasMatch(token)) {
      throw const FormatException('유효한 연결 코드가 아니에요. 새 QR을 발급해 주세요.');
    }
    return ConnectionLink(kind, token);
  }
}
