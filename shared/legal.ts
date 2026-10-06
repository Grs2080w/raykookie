// Termos de Serviço e Política de Privacidade da raykookie: uma única fonte de texto para o site (páginas
// /termos e /privacidade) e para os apps (que leem de GET /api/legal/<documento>).
// Revise com um advogado antes de divulgar e preencha os dados da empresa em LEGAL_ENTITY.
import { leadDaysLabel, MAX_QTY } from './shop';
import { REFUND_WINDOW_HOURS } from './refund';

/** Dados de quem opera a loja. Campos vazios não aparecem nos documentos. */
export const LEGAL_ENTITY = {
  name: 'Raykookie',
  legalName: '', // razão social ou nome completo do responsável, quando houver
  cnpj: '', // CNPJ ou CPF
  address: '', // endereço da confeiteira
  email: '', // e-mail de contato e do encarregado de dados
};

export const LEGAL_UPDATED = '3 de outubro de 2026';

export type LegalSection = { h: string; p?: string[]; ul?: string[] };
export type LegalDoc = { id: LegalId; title: string; summary: string; updated: string; sections: LegalSection[] };
export type LegalId = 'termos' | 'privacidade';

export const LEGAL_IDS: LegalId[] = ['termos', 'privacidade'];
export const LEGAL_PATH: Record<LegalId, string> = { termos: '/termos', privacidade: '/privacidade' };
export const LEGAL_LABEL: Record<LegalId, string> = { termos: 'Termos de Serviço', privacidade: 'Política de Privacidade' };
export const isLegalId = (v: unknown): v is LegalId => LEGAL_IDS.includes(v as LegalId);

const E = LEGAL_ENTITY;
const who = [E.name, E.legalName && `(${E.legalName})`, E.cnpj && `inscrita sob o nº ${E.cnpj}`, E.address && `com endereço em ${E.address}`].filter(Boolean).join(', ');
const contact = E.email
  ? `pelo e-mail ${E.email} ou pelo WhatsApp informado no site e no aplicativo`
  : 'pelo WhatsApp e demais canais de atendimento informados no site e no aplicativo';

const TERMOS: LegalDoc = {
  id: 'termos',
  title: 'Termos de Serviço',
  summary: 'Regras para usar o site e o aplicativo da Raykookie, fazer encomendas e pagar.',
  updated: LEGAL_UPDATED,
  sections: [
    {
      h: '1. Quem somos e o que são estes termos',
      p: [
        `Estes Termos de Serviço valem para o site e para o aplicativo ${E.name}. A ${who} é uma confeiteira que vende doces, bolos, cookies, brownies e tortas feitos à mão, sob encomenda.`,
        'Ao criar uma conta, fazer um pedido ou usar o site ou o aplicativo, você declara que leu e concorda com estes termos e com a Política de Privacidade. Se não concordar, não use os serviços.',
      ],
    },
    {
      h: '2. O que oferecemos',
      ul: [
        'Site e aplicativo: ver o cardápio, criar uma conta, fazer encomendas, pagar com Pix, acompanhar, cancelar ou pedir devolução de pedidos e cuidar dos seus dados.',
        'Os serviços podem mudar, ser interrompidos para manutenção ou deixar de existir, com aviso quando possível.',
      ],
    },
    {
      h: '3. Sua conta',
      ul: [
        'Para pedir é preciso criar uma conta com e-mail e senha ou entrar com a sua conta Google. Informe dados verdadeiros e mantenha-os atualizados.',
        'Você é responsável por manter a senha em segredo e por tudo o que for feito na sua conta. Avise-nos se suspeitar de uso indevido.',
        'Pessoas menores de 18 anos só devem usar o serviço com o acompanhamento de um responsável legal.',
      ],
    },
    {
      h: '4. Encomendas',
      ul: [
        `Trabalhamos somente sob encomenda: escolha a data da entrega ou retirada com pelo menos ${leadDaysLabel()}.`,
        `O cardápio, os preços e as quantidades mostrados no site e no aplicativo são os válidos no momento do pedido. O limite é de ${MAX_QTY} unidades por item em cada pedido; para quantidades maiores, fale com a gente.`,
        'Você escolhe retirar no local ou receber em casa. Se houver taxa de entrega, avisamos pelo WhatsApp. Informe o endereço completo e correto: atrasos ou falhas causados por dados errados são de responsabilidade de quem os informou.',
        'Todo o acompanhamento do pedido é feito pelo site e pelo aplicativo, onde você vê cada etapa (recebido, em preparo, pronto e entregue). Só entramos em contato pelo WhatsApp ou pelo telefone informado quando for necessário, por exemplo para tirar uma dúvida sobre o pedido.',
        'Podemos recusar ou ajustar pedidos que não conseguirmos atender, avisando você.',
        'Pedidos personalizados (sabor, tamanho, decoração, recado) devem ser descritos no campo de observações.',
      ],
    },
    {
      h: '5. Preços e pagamento',
      ul: [
        'As formas de pagamento disponíveis são Pix e dinheiro (na entrega ou retirada, com troco quando informado).',
        'No Pix, o código é gerado pelo Mercado Pago ao finalizar o pedido. O pedido só é considerado pago depois que o Mercado Pago confirma a aprovação do pagamento. Gerar ou copiar o código não significa que o pagamento foi feito.',
        'O código Pix tem prazo de validade, mostrado no pedido. Depois que vence, não pague esse código: gere um novo pelo site ou pelo aplicativo. Pagamentos feitos depois do vencimento não são aceitos e o valor volta para quem pagou, conforme as regras do Mercado Pago.',
        'Não recebemos nem guardamos dados de conta ou de cartão. O pagamento é processado pelo Mercado Pago, sob os termos dele.',
        'Os preços são em reais e podem mudar sem aviso, mas não alteram pedidos já confirmados.',
      ],
    },
    {
      h: '6. Cancelamentos, devoluções e reembolsos',
      p: [
        'Cada pedido é preparado só para você. Por isso, o que dá para cancelar ou devolver depende do andamento da produção, que você acompanha na tela do pedido, no site ou no aplicativo:',
      ],
      ul: [
        'Antes de começarmos a produzir: você mesmo cancela na tela do pedido. Se o pedido já foi pago, o valor do Pix volta automaticamente, na hora, para a conta que pagou. Se havia um Pix pendente, o código é desativado.',
        `Depois que a produção começou (em produção, pronto ou entregue): você pode pedir a devolução na tela do pedido, informando o motivo, descrevendo o que aconteceu e, quando já recebeu o produto, enviando de 1 a 5 fotos. Depois da entrega, o pedido de devolução pode ser feito em até ${REFUND_WINDOW_HOURS} horas.`,
        'Nesses casos não há garantia de devolução, nem total nem parcial: a confeiteira analisa cada pedido e decide, com a resposta e o motivo mostrados na tela do pedido. O valor devolvido pode ser total ou parcial.',
        'Se nós cancelarmos um pedido já pago (por exemplo, por falta de ingrediente), devolvemos o valor total automaticamente.',
        'A devolução é feita por Pix para a mesma conta que pagou, em geral em poucos minutos, pelo Mercado Pago. Em casos raros pode levar mais tempo. O Pix só pode ser devolvido em até 90 dias depois do pagamento.',
        'Pagamentos feitos depois de o pedido ser cancelado também são devolvidos automaticamente.',
        'As fotos enviadas no pedido de devolução são vistas só por nós, usadas apenas para analisar o pedido e apagadas depois de 180 dias.',
        `Para pedir alterações no pedido, ou se você não conseguir usar o aplicativo, fale com a gente ${contact}. Os direitos garantidos pelo Código de Defesa do Consumidor continuam valendo.`,
      ],
    },
    {
      h: '7. Alimentos, ingredientes e alergias',
      p: [
        'Nossos produtos podem conter ou ter contato com leite, ovos, glúten, castanhas, amendoim, soja e outros alérgenos. Se você ou quem vai comer tem alergia ou restrição alimentar, descreva no campo de observações e confirme com a gente antes de finalizar. Não conseguimos garantir ausência total de contato cruzado.',
      ],
    },
    {
      h: '8. Uso adequado',
      ul: [
        'Não use os serviços para fraudar, enviar conteúdo ilegal ou ofensivo, tentar acessar áreas ou dados de outras pessoas, sobrecarregar ou prejudicar o funcionamento do sistema.',
        'Não copie, revenda nem modifique o aplicativo ou o site, exceto no que a lei permitir.',
        'Podemos suspender ou encerrar contas usadas em desacordo com estes termos.',
      ],
    },
    {
      h: '9. Propriedade intelectual',
      p: [
        'A marca Raykookie, o logotipo, as fotos, os textos, as receitas e o software do site e do aplicativo pertencem à Raykookie ou a quem nos licenciou. Você pode usá-los apenas para acessar e usar os serviços.',
      ],
    },
    {
      h: '10. Disponibilidade e responsabilidade',
      p: [
        'Fazemos o possível para manter o site e o aplicativo no ar e seguros, mas não garantimos funcionamento sem interrupções ou erros. Na medida permitida pela lei, não respondemos por falhas de internet, do aparelho, de serviços de terceiros (como Mercado Pago, Google e operadoras) ou por situações fora do nosso controle. Nada nestes termos afasta responsabilidades que a lei não permite afastar.',
      ],
    },
    {
      h: '11. Mudanças nestes termos',
      p: [
        'Podemos atualizar estes termos. A versão em vigor é a publicada nesta página, com a data da última atualização. Mudanças importantes serão avisadas no site ou no aplicativo. Continuar usando os serviços depois da mudança significa que você concorda com ela.',
      ],
    },
    {
      h: '12. Lei aplicável e foro',
      p: ['Estes termos seguem as leis do Brasil. Para consumidores, fica eleito o foro do domicílio do consumidor, como prevê o Código de Defesa do Consumidor.'],
    },
    {
      h: '13. Fale com a gente',
      p: [`Dúvidas, pedidos ou reclamações: ${contact}.`],
    },
  ],
};

const PRIVACIDADE: LegalDoc = {
  id: 'privacidade',
  title: 'Política de Privacidade',
  summary: 'Quais dados coletamos, para que usamos, com quem compartilhamos e como você exerce seus direitos (LGPD).',
  updated: LEGAL_UPDATED,
  sections: [
    {
      h: '1. Quem é responsável pelos seus dados',
      p: [
        `A ${who} é a controladora dos dados pessoais tratados no site e no aplicativo, nos termos da Lei Geral de Proteção de Dados (LGPD, Lei nº 13.709/2018). Para falar sobre seus dados, use o canal indicado na seção 11.`,
      ],
    },
    {
      h: '2. Dados que coletamos',
      ul: [
        'Cadastro do cliente: nome, e-mail, telefone e senha (guardada de forma protegida, nunca em texto aberto). Se você entra com o Google, recebemos o seu nome e e-mail dessa conta.',
        'Pedidos: itens, quantidades, valores, data e período escolhidos, forma de pagamento, troco e observações. Para entrega, também o CEP, a rua, o número, o complemento, o bairro e a cidade. Guardamos o último endereço para preencher o próximo pedido.',
        'Pedidos de devolução: o motivo, a descrição, os itens e as fotos que você enviar. As fotos ficam guardadas de forma protegida, são vistas só por nós e são apagadas 180 dias depois da decisão. Antes de enviar, o aplicativo e o site removem dados escondidos da foto, como a localização.',
        'Pagamento por Pix: o identificador do pagamento, o código Pix e o status (aprovado ou não). Para gerar o Pix enviamos ao Mercado Pago o valor, seu e-mail e seu primeiro nome. Não recebemos nem guardamos dados de cartão ou de conta bancária.',
        'Notificações do aplicativo (se você permitir): o identificador do aparelho para avisos do pedido, guardado enquanto você estiver logado nele. Você pode desligar nas configurações do celular a qualquer momento.',
        'Dados técnicos: cookies de sessão para manter você conectado, e registros do servidor (como horário, endereço de rede e erros), usados para segurança e funcionamento.',
        'Login com o Google (se você escolher): recebemos apenas o seu nome e o seu e-mail, com a permissão que você concede na tela do Google. Não acessamos contatos, arquivos, agenda nem qualquer outro dado da sua conta Google, e não publicamos nada em seu nome. Você pode retirar a permissão a qualquer momento em myaccount.google.com/permissions.',
      ],
    },
    {
      h: '3. Para que usamos e em que base legal',
      ul: [
        'Criar e manter sua conta, receber, preparar, entregar e cobrar seus pedidos, e falar com você sobre eles: execução do contrato e procedimentos preliminares.',
        'Confirmar pagamentos e evitar fraudes, manter a segurança e o bom funcionamento do sistema: legítimo interesse e prevenção a fraudes.',
        'Cumprir obrigações legais e fiscais e defender nossos direitos: obrigação legal e exercício regular de direitos.',
        'Mostrar o andamento do seu pedido no site e no aplicativo e, somente quando necessário, falar com você por WhatsApp ou telefone: execução do contrato. Não enviamos propaganda sem a sua autorização.',
        'Quando o uso depender de consentimento, você poderá retirá-lo a qualquer momento.',
      ],
    },
    {
      h: '4. Com quem compartilhamos',
      p: ['Não vendemos seus dados. Compartilhamos só o necessário com:'],
      ul: [
        'Mercado Pago: processa o pagamento por Pix e nos informa se foi aprovado.',
        'Google: login com a conta Google, quando você escolhe essa opção (só recebemos o seu nome e o seu e-mail, que não são compartilhados nem usados para publicidade), e o serviço de notificações do aplicativo (Firebase Cloud Messaging), que recebe o identificador do aparelho e o texto de cada aviso do pedido.',
        'ViaCEP: ao digitar o CEP, o site ou o aplicativo consulta esse serviço público para preencher o endereço. Apenas o CEP é enviado.',
        'Provedores de hospedagem e infraestrutura que mantêm o sistema no ar, sob dever de confidencialidade.',
        'Autoridades públicas, quando exigido por lei ou ordem judicial.',
      ],
    },
    {
      h: '5. Cookies e armazenamento no aparelho',
      p: [
        'Usamos apenas cookies essenciais, para manter sua sessão e proteger a conta. No aplicativo, o token de acesso fica no cofre seguro do aparelho (Keystore no Android). Não usamos cookies nem ferramentas de publicidade ou de rastreamento de comportamento.',
      ],
    },
    {
      h: '6. Por quanto tempo guardamos',
      p: [
        'Guardamos os dados enquanto sua conta existir e pelo tempo necessário para cumprir obrigações legais, resolver disputas e manter o histórico dos pedidos. Depois, eliminamos ou anonimizamos os dados, salvo se a lei exigir a guarda por mais tempo. Se você excluir a conta, apagamos seus dados pessoais (nome, e-mail, telefone, endereço e login) e mantemos apenas o registro dos pedidos e pagamentos, sem identificar você, pelo prazo que a lei exigir.',
      ],
    },
    {
      h: '7. Seus direitos',
      p: ['Pela LGPD, você pode pedir, a qualquer momento e gratuitamente:'],
      ul: [
        'confirmação de que tratamos seus dados e acesso a eles;',
        'correção de dados incompletos, inexatos ou desatualizados;',
        'anonimização, bloqueio ou eliminação de dados desnecessários ou tratados em desacordo com a lei;',
        'portabilidade dos dados, quando aplicável;',
        'eliminação dos dados tratados com o seu consentimento, e informação sobre com quem compartilhamos;',
        'informação sobre a possibilidade de não consentir e suas consequências, e revogação do consentimento;',
        'exclusão da sua conta. Alguns dados podem ser mantidos quando a lei exigir.',
      ],
    },
    {
      h: '8. Como exercer seus direitos',
      p: [
        'Você mesmo faz tudo isso no aplicativo e no site, em Minha conta → Meus dados, sem precisar falar com ninguém:',
      ],
      ul: [
        'ver o que guardamos sobre você (confirmação e acesso);',
        'corrigir nome, telefone e endereço;',
        'baixar uma cópia dos seus dados em formato aberto (portabilidade);',
        'ver com quem compartilhamos e como retirar a permissão do Google;',
        'fazer uma solicitação de anonimização, bloqueio ou eliminação de dados, que respondemos em até 15 dias;',
        'excluir a sua conta. Só não é possível enquanto houver pedido em andamento, e alguns registros são mantidos quando a lei exigir.',
        `Se preferir, envie o pedido ${contact}, informando o e-mail da sua conta. Podemos pedir uma confirmação de identidade para proteger você. Também é possível reclamar à Autoridade Nacional de Proteção de Dados (ANPD). A página /excluir-conta explica a exclusão da conta mesmo para quem não está logado.`,
      ],
    },
    {
      h: '9. Segurança',
      p: [
        'Usamos conexão criptografada (HTTPS), senhas guardadas de forma protegida, acesso restrito aos dados e conferência da assinatura das notificações de pagamento. Nenhum sistema é totalmente imune a falhas: se houver um incidente que possa causar risco relevante a você, avisaremos você e a ANPD, como a lei determina.',
      ],
    },
    {
      h: '10. Crianças e adolescentes',
      p: ['Os serviços não são voltados a menores de 18 anos. Pessoas menores só devem usá-los com o acompanhamento de um responsável legal, que responde pelo cadastro e pelos pedidos.'],
    },
    {
      h: '11. Transferência para fora do Brasil, mudanças e contato',
      ul: [
        'Alguns provedores, como o Google, podem tratar dados em servidores fora do Brasil, com as garantias previstas na LGPD.',
        'Podemos atualizar esta política. A versão em vigor é a publicada aqui, com a data da última atualização, e mudanças importantes serão avisadas no site ou no aplicativo.',
        `Contato sobre privacidade e dados pessoais: ${contact}.`,
      ],
    },
  ],
};

export const LEGAL_DOCS: Record<LegalId, LegalDoc> = { termos: TERMOS, privacidade: PRIVACIDADE };
