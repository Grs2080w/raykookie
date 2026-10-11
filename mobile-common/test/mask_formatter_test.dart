import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/ui/widgets.dart';

void main() {
  final cpf = MaskFormatter(format: formatCpf, warning: 'No CPF só entram números.');
  String expiry(String v) {
    final d = v.replaceAll(RegExp(r'\D'), '');
    return d.length > 2 ? '${d.substring(0, 2)}/${d.substring(2)}' : d;
  }
  final validade = MaskFormatter(format: expiry, warning: 'Na validade só entram números.');

  bool warns(MaskFormatter m, String before, String after) => m.hasInvalidInput(before, after, m.format(after));

  test('máscara: digitar números num campo já formatado não avisa', () {
    expect(warns(cpf, '123.456', '123.4567'), isFalse);
    expect(warns(cpf, '123.456.789', '123.456.7890'), isFalse);
    expect(warns(validade, '12/3', '12/30'), isFalse);
    expect(warns(validade, '12', '123'), isFalse);
  });

  test('máscara: colar valor já formatado não avisa; letra ou símbolo estranho avisa', () {
    expect(warns(cpf, '', '529.982.247-25'), isFalse);
    expect(warns(cpf, '123.456', '123.456a'), isTrue);
    expect(warns(validade, '12/3', '12/3x'), isTrue);
    expect(warns(cpf, '123', '123,'), isTrue);
  });

  test('máscara: mudança sem texto novo (trocar de campo, apagar) não avisa', () {
    expect(warns(cpf, '123.456.789-09', '123.456.789-09'), isFalse);
    expect(warns(validade, '12/30', '12/3'), isFalse);
  });
}
