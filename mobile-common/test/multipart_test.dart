import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:raykookie_common/core/api_client.dart';

void main() {
  test('postMultipart manda campos e arquivos como multipart/form-data, com o token', () async {
    late http.BaseRequest seen;
    late List<int> bytes;
    final api = ApiClient(base: 'http://x', client: _Spy((r, b) {
      seen = r;
      bytes = b;
      return http.StreamedResponse(Stream.value(utf8.encode('{"ok":true}')), 201, headers: {'content-type': 'application/json'});
    }))..token = 'tok';
    final r = await api.postMultipart('/api/x', {'reason_code': 'avariado', 'items': '[{"id":"1","qty":1}]'},
        [(name: 'fotos', filename: 'foto-1.jpg', bytes: Uint8List.fromList([1, 2, 3]), contentType: 'image/jpeg')]);
    expect(r, {'ok': true});
    expect(seen.method, 'POST');
    expect(seen.headers['authorization'], 'Bearer tok');
    expect(seen.headers['content-type'], startsWith('multipart/form-data; boundary='));
    final text = latin1.decode(bytes);
    expect(text, contains('name="reason_code"'));
    expect(text, contains('avariado'));
    expect(text, contains('name="fotos"; filename="foto-1.jpg"'));
    expect(text, contains('content-type: image/jpeg'));
  });

  test('delete aceita corpo (confirmação da exclusão da conta)', () async {
    late http.BaseRequest seen;
    late List<int> bytes;
    final api = ApiClient(base: 'http://x', client: _Spy((r, b) {
      seen = r;
      bytes = b;
      return http.StreamedResponse(Stream.value(utf8.encode('{}')), 200, headers: {'content-type': 'application/json'});
    }));
    await api.delete('/api/cliente/me', {'confirm': 'EXCLUIR'});
    expect(seen.method, 'DELETE');
    expect(jsonDecode(utf8.decode(bytes)), {'confirm': 'EXCLUIR'});
  });
}

class _Spy extends http.BaseClient {
  _Spy(this.on);
  final http.StreamedResponse Function(http.BaseRequest, List<int>) on;
  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async => on(request, await request.finalize().toBytes());
}
