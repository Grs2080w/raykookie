import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_gestao/core/pix/br_code.dart';

void main() {
  test('CRC16-CCITT bate com o vetor padrão', () => expect(crc16('123456789'), '29B1'));

  test('payload estático com valor: estrutura e CRC', () {
    const cfg = PixConfig(kind: PixKeyKind.email, key: 'Ray@Exemplo.com', name: 'Raykookie Confeitaria', city: 'São Paulo');
    final p = buildPixPayload(cfg, 12.5);
    expect(p, startsWith('000201'));
    expect(p, contains('0014br.gov.bcb.pix0115ray@exemplo.com'));
    expect(p, contains('540512.50'));
    expect(p, contains('5802BR'));
    expect(p, contains('5921RAYKOOKIE CONFEITARIA'));
    expect(p, contains('6009SAO PAULO'));
    expect(p, contains('62070503***'));
    expect(p.substring(p.length - 8, p.length - 4), '6304');
    expect(p.substring(p.length - 4), crc16(p.substring(0, p.length - 4)));
  });

  test('chaves: validação por tipo', () {
    expect(normalizePixKey(PixKeyKind.cpf, '529.982.247-25'), '52998224725');
    expect(normalizePixKey(PixKeyKind.cpf, '111.111.111-11'), isNull);
    expect(normalizePixKey(PixKeyKind.cnpj, '11.222.333/0001-81'), '11222333000181');
    expect(normalizePixKey(PixKeyKind.cnpj, '11.222.333/0001-82'), isNull);
    expect(normalizePixKey(PixKeyKind.phone, '(11) 98765-4321'), '+5511987654321');
    expect(normalizePixKey(PixKeyKind.phone, '123'), isNull);
    expect(normalizePixKey(PixKeyKind.random, '123E4567-E89B-12D3-A456-426614174000'), '123e4567-e89b-12d3-a456-426614174000');
    expect(normalizePixKey(PixKeyKind.email, 'sem-arroba'), isNull);
  });

  test('nome e cidade cortam no limite e perdem acento', () {
    expect(pixText('Confeitaria Açúcar & Afeto da Ray Kookie', 25).length, lessThanOrEqualTo(25));
    expect(pixText('São José', 15), 'SAO JOSE');
  });

  test('valor inválido ou chave inválida não geram payload', () {
    const ok = PixConfig(kind: PixKeyKind.cpf, key: '52998224725', name: 'A', city: 'B');
    expect(() => buildPixPayload(ok, 0), throwsArgumentError);
    expect(() => buildPixPayload(const PixConfig(kind: PixKeyKind.cpf, key: '1', name: 'A', city: 'B'), 5), throwsArgumentError);
  });
}
