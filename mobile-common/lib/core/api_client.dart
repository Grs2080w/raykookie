import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;

import 'config.dart';

class ApiException implements Exception {
  ApiException(this.status, this.message);
  final int status;
  final String message;
  bool get unauthorized => status == 401;
  @override
  String toString() => message;
}

/// Cliente HTTP do backend. Cada perfil (confeiteira e cliente) usa a sua instância, com o seu token.
class ApiClient {
  ApiClient({http.Client? client, String? base}) : _http = client ?? http.Client(), base = (base ?? AppConfig.apiBase).replaceAll(RegExp(r'/$'), '');

  final http.Client _http;
  final String base;
  String? token;

  /// Chamado quando o servidor responde 401 com um token salvo (sessão expirada).
  void Function()? onUnauthorized;

  Map<String, String> get _headers => {
        'x-client': 'mobile', // pede o token no corpo do login
        if (token != null) 'authorization': 'Bearer $token',
      };

  Future<dynamic> get(String path) => _send('GET', path);
  Future<dynamic> post(String path, [Object? body]) => _send('POST', path, body: body);
  Future<dynamic> put(String path, [Object? body]) => _send('PUT', path, body: body);
  Future<dynamic> patch(String path, [Object? body]) => _send('PATCH', path, body: body);
  Future<dynamic> delete(String path, [Object? body]) => _send('DELETE', path, body: body);

  /// Baixa um arquivo (ex.: a imagem do story) como bytes.
  Future<Uint8List> getBytes(String path) async {
    final req = http.Request('GET', Uri.parse('$base$path'))..headers.addAll(_headers);
    try {
      final res = await http.Response.fromStream(await _http.send(req).timeout(const Duration(seconds: 60)));
      if (res.statusCode < 200 || res.statusCode >= 300) {
        throw ApiException(res.statusCode, 'Não foi possível gerar a imagem. Tente de novo.');
      }
      return res.bodyBytes;
    } on ApiException {
      rethrow;
    } catch (_) {
      throw ApiException(0, 'Sem conexão com o servidor. Confira a internet e tente de novo.');
    }
  }

  /// multipart/form-data: campos de texto e arquivos (ex.: pedido de devolução com fotos).
  Future<dynamic> postMultipart(String path, Map<String, String> fields, List<({String name, String filename, Uint8List bytes, String contentType})> files) =>
      _send('POST', path, multipart: (fields: fields, files: files));

  /// Envia um arquivo (foto) como corpo bruto.
  Future<dynamic> putBytes(String path, Uint8List bytes, String contentType) => _send('PUT', path, raw: bytes, contentType: contentType);

  Future<dynamic> _send(String method, String path, {Object? body, Uint8List? raw, String? contentType, ({Map<String, String> fields, List<({String name, String filename, Uint8List bytes, String contentType})> files})? multipart}) async {
    final http.BaseRequest req;
    if (multipart != null) {
      final m = http.MultipartRequest(method, Uri.parse('$base$path'));
      m.fields.addAll(multipart.fields);
      for (final f in multipart.files) {
        m.files.add(http.MultipartFile.fromBytes(f.name, f.bytes, filename: f.filename, contentType: MediaType.parse(f.contentType)));
      }
      req = m;
    } else {
      req = http.Request(method, Uri.parse('$base$path'));
    }
    req.headers.addAll(_headers);
    if (req is http.Request && raw != null) {
      req.headers['content-type'] = contentType ?? 'application/octet-stream';
      req.bodyBytes = raw;
    } else if (req is http.Request && body != null) {
      req.headers['content-type'] = 'application/json';
      req.body = jsonEncode(body);
    }
    http.Response res;
    try {
      res = await http.Response.fromStream(await _http.send(req).timeout(const Duration(seconds: 30)));
    } on TimeoutException {
      throw ApiException(0, 'O servidor demorou para responder. Tente de novo.');
    } catch (_) {
      throw ApiException(0, 'Sem conexão com o servidor. Confira a internet e tente de novo.');
    }
    dynamic data;
    if (res.body.isNotEmpty) {
      try {
        data = jsonDecode(utf8.decode(res.bodyBytes));
      } catch (_) {
        data = null;
      }
    }
    if (res.statusCode == 401 && token != null) onUnauthorized?.call();
    if (res.statusCode < 200 || res.statusCode >= 300) {
      final msg = data is Map && data['error'] is String ? data['error'] as String : 'Não foi possível concluir. Tente de novo.';
      throw ApiException(res.statusCode, msg);
    }
    return data;
  }

  /// URL de foto de receita (as fotos são públicas; a URL já traz a versão).
  String absolute(String path) => path.startsWith('http') ? path : '$base$path';

  void close() => _http.close();
}
