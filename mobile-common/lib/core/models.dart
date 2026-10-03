import 'pricing.dart';

double _d(dynamic v) => v is num ? v.toDouble() : (v is String ? double.tryParse(v) ?? 0 : 0);
int? _i(dynamic v) => v is num ? v.toInt() : null;
String _s(dynamic v) => v?.toString() ?? '';

class Category {
  Category({required this.id, required this.kind, required this.name, required this.icon, required this.color, required this.uses});
  final int id;
  final String kind; // ingredient | recipe | expense
  final String name, icon, color;
  final int uses;
  factory Category.fromJson(Map<String, dynamic> j) =>
      Category(id: _i(j['id'])!, kind: _s(j['kind']), name: _s(j['name']), icon: _s(j['icon']), color: _s(j['color']), uses: _i(j['uses']) ?? 0);
}

class Ingredient {
  Ingredient({
    required this.id, this.categoryId, required this.name, required this.unit, required this.stockQty, required this.packageSize,
    required this.packageCost, required this.minStock, required this.supplier, required this.notes,
  });
  final int id;
  final int? categoryId;
  final String name, unit, supplier, notes;
  final double stockQty, packageSize, packageCost, minStock;
  double get unitCostValue => unitCost(packageCost, packageSize);
  factory Ingredient.fromJson(Map<String, dynamic> j) => Ingredient(
        id: _i(j['id'])!, categoryId: _i(j['category_id']), name: _s(j['name']), unit: _s(j['unit']), stockQty: _d(j['stock_qty']),
        packageSize: _d(j['package_size']), packageCost: _d(j['package_cost']), minStock: _d(j['min_stock']), supplier: _s(j['supplier']), notes: _s(j['notes']),
      );
}

class Settings {
  Settings({required this.hourlyRate, required this.monthlyUnits, this.minMarginPct = 30});
  final double hourlyRate, monthlyUnits, minMarginPct; // minMarginPct: abaixo disso o app avisa que a margem caiu
  factory Settings.fromJson(Map<String, dynamic> j) => Settings(hourlyRate: _d(j['hourly_rate']), monthlyUnits: _d(j['monthly_units']), minMarginPct: j['min_margin_pct'] == null ? 30 : _d(j['min_margin_pct']));
}

class FixedCost {
  FixedCost({required this.id, this.categoryId, required this.name, required this.kind, required this.amount});
  final int id;
  final int? categoryId;
  final String name, kind; // fixed | variable
  final double amount;
  factory FixedCost.fromJson(Map<String, dynamic> j) =>
      FixedCost(id: _i(j['id'])!, categoryId: _i(j['category_id']), name: _s(j['name']), kind: _s(j['kind']), amount: _d(j['amount']));
}

class OrderModel {
  OrderModel({
    required this.id, required this.number, required this.orderDate, required this.customer, this.recipeId, required this.product,
    required this.qty, required this.unitPrice, this.deliveryDate, required this.status, required this.payment, required this.notes, this.address = '',
    this.shopOrderId, this.shopCode, this.shopPaid = false, this.shopTotal = 0, this.shopRefunded = 0, this.shopCancel, this.shopRefund,
  });
  final int id, number;
  final int? shopOrderId, shopCode; // pedido online: id interno e o número que o cliente vê
  final bool shopPaid;
  final double shopTotal, shopRefunded; // pedido online: total e quanto já foi devolvido
  final Map<String, dynamic>? shopCancel; // pedido online cancelado: {by, reason, note}
  final Map<String, dynamic>? shopRefund; // a devolução mais recente: {id, kind, status, reason_code, requested_amount, approved_amount, photos}
  bool get refundWaiting => shopRefund?['status'] == 'solicitado';
  final int? recipeId;
  final String orderDate, customer, product, status, payment, notes, address;
  final String? deliveryDate;
  final double qty, unitPrice;
  double get total => qty * unitPrice;
  /// Pedido registrado à mão (não veio do app), ainda não pago e não cancelado: dá para mandar um link de pagamento Pix.
  bool get canPayLink => shopOrderId == null && status != 'cancelado' && payment != 'pago';
  factory OrderModel.fromJson(Map<String, dynamic> j) => OrderModel(
        id: _i(j['id'])!, number: _i(j['number']) ?? 0, orderDate: _s(j['order_date']), customer: _s(j['customer']), recipeId: _i(j['recipe_id']),
        product: _s(j['product']), qty: _d(j['qty']), unitPrice: _d(j['unit_price']), deliveryDate: j['delivery_date'] as String?,
        status: _s(j['status']), payment: _s(j['payment']), notes: _s(j['notes']), address: _s(j['address']),
        shopOrderId: _i(j['shop_order_id']), shopCode: _i(j['shop_code']), shopPaid: j['shop_paid'] == true, shopTotal: _d(j['shop_total']), shopRefunded: _d(j['shop_refunded']),
        shopCancel: j['shop_cancel'] is Map ? Map<String, dynamic>.from(j['shop_cancel'] as Map) : null,
        shopRefund: j['shop_refund'] is Map ? Map<String, dynamic>.from(j['shop_refund'] as Map) : null,
      );
}

class RecipeItem {
  RecipeItem(this.ingredientId, this.qty);
  final int ingredientId;
  final double qty;
}

class RecipeRow {
  RecipeRow({
    required this.id, required this.name, this.categoryId, required this.yieldUnits, required this.marginPct, required this.prepHours,
    required this.notes, this.unitWeightG = 0, this.salePrice, this.photoV, required this.items, this.pricing,
  });
  final int id;
  final String name, notes;
  final int? categoryId;
  final double yieldUnits, marginPct, prepHours, unitWeightG; // gramatura de cada unidade (g); 0 = não informada
  final double? salePrice; // preço do cardápio fixado; nulo = o calculado
  final String? photoV;
  final List<RecipeItem> items;
  final PricingResult? pricing; // só vem na listagem precificada
  factory RecipeRow.fromJson(Map<String, dynamic> j) => RecipeRow(
        id: _i(j['id'])!, name: _s(j['name']), categoryId: _i(j['category_id']), yieldUnits: _d(j['yield_units']), marginPct: _d(j['margin_pct']),
        prepHours: _d(j['prep_hours']), notes: _s(j['notes']), unitWeightG: _d(j['unit_weight_g']), salePrice: j['sale_price'] == null ? null : _d(j['sale_price']), photoV: j['photo_v'] as String?,
        items: [for (final it in (j['items'] as List? ?? [])) RecipeItem(_i(it['ingredient_id'])!, _d(it['qty']))],
        pricing: j['pricing'] is Map<String, dynamic> ? PricingResult.fromJson(j['pricing'] as Map<String, dynamic>) : null,
      );
}

class Goal {
  Goal({required this.revenue, required this.orders, required this.newCustomers, required this.avgTicket});
  final double revenue, avgTicket;
  final int orders, newCustomers;
  factory Goal.fromJson(Map<String, dynamic> j) =>
      Goal(revenue: _d(j['revenue']), orders: _d(j['orders']).round(), newCustomers: _d(j['new_customers']).round(), avgTicket: _d(j['avg_ticket']));
}

class MonthStats {
  MonthStats({required this.revenue, required this.profit, required this.orders, required this.avgTicket, required this.newCustomers});
  final double revenue, profit, avgTicket;
  final int orders, newCustomers;
  factory MonthStats.fromJson(Map<String, dynamic> j) =>
      MonthStats(revenue: _d(j['revenue']), profit: _d(j['profit']), orders: _d(j['orders']).round(), avgTicket: _d(j['avg_ticket']), newCustomers: _d(j['new_customers']).round());
}

class Dashboard {
  Dashboard({
    required this.username, required this.month, required this.ingredientCount, required this.goal, required this.stats, required this.receivable,
    required this.empty, required this.low, required this.upcoming, required this.best, this.refundsWaiting = 0, this.refundsPending = 0, this.refundsFailed = 0,
  });
  final int refundsWaiting, refundsFailed; // devoluções aguardando decisão / que falharam
  final double refundsPending; // valor de devoluções em processamento (precisa estar no Mercado Pago)
  final String username, month;
  final int ingredientCount;
  final Goal goal;
  final MonthStats stats;
  final double receivable;
  final bool empty;
  final List<Ingredient> low;
  final List<OrderModel> upcoming;
  final List<RecipeRow> best;
  factory Dashboard.fromJson(Map<String, dynamic> j) => Dashboard(
        username: _s(j['username']), month: _s(j['month']), ingredientCount: _i(j['ingredientCount']) ?? 0, goal: Goal.fromJson(j['goal']),
        stats: MonthStats.fromJson(j['stats']), receivable: _d(j['receivable']), empty: j['empty'] == true,
        low: [for (final e in j['low'] as List) Ingredient.fromJson(e)], upcoming: [for (final e in j['upcoming'] as List) OrderModel.fromJson(e)],
        best: [for (final e in j['best'] as List) RecipeRow.fromJson(e)],
        refundsWaiting: _i(j['refundsWaiting']) ?? 0, refundsPending: _d(j['refundsPending']), refundsFailed: _i(j['refundsFailed']) ?? 0,
      );
}

// ---- loja -----------------------------------------------------------------------------------------

class MenuItem {
  MenuItem({required this.id, required this.name, required this.price, required this.category, this.image, this.weightG = 0});
  final String id, name, category;
  final double price, weightG;
  final String? image;
  factory MenuItem.fromJson(Map<String, dynamic> j) =>
      MenuItem(id: _s(j['id']), name: _s(j['name']), price: _d(j['price']), category: _s(j['category']), image: j['image'] as String?, weightG: _d(j['weight_g']));
}

class MenuGroup {
  MenuGroup({required this.id, required this.name, required this.items});
  final String id, name;
  final List<MenuItem> items;
}

class Menu {
  Menu(this.items, this.groups);
  final List<MenuItem> items;
  final List<MenuGroup> groups;
  factory Menu.empty() => Menu([], []);
  factory Menu.fromJson(Map<String, dynamic> j) => Menu(
        [for (final e in j['items'] as List) MenuItem.fromJson(e)],
        [
          for (final g in j['groups'] as List)
            MenuGroup(id: _s(g['id']), name: _s(g['name']), items: [for (final e in g['items'] as List) MenuItem.fromJson(e)])
        ],
      );
}

class CustomerProfile {
  CustomerProfile({
    required this.id, required this.email, required this.name, required this.phone, required this.cep, required this.street,
    required this.number, required this.complement, required this.district, required this.city,
  });
  final int id;
  final String email, name, phone, cep, street, number, complement, district, city;
  factory CustomerProfile.fromJson(Map<String, dynamic> j) => CustomerProfile(
        id: _i(j['id'])!, email: _s(j['email']), name: _s(j['name']), phone: _s(j['phone']), cep: _s(j['cep']), street: _s(j['street']),
        number: _s(j['number']), complement: _s(j['complement']), district: _s(j['district']), city: _s(j['city']),
      );
}

class ShopOrderItem {
  ShopOrderItem({required this.id, required this.name, required this.qty, required this.unitPrice});
  final String id, name;
  final int qty;
  final double unitPrice;
}

/// Cancelamento do pedido: quem cancelou (cliente, loja ou sistema), o motivo (da lista) e a mensagem livre.
class OrderCancel {
  OrderCancel({required this.by, required this.reason, this.code = '', this.note = '', this.at = ''});
  final String by, code, reason, note, at;
  /// Encerrado porque todo o valor foi devolvido (não foi ninguém que cancelou).
  bool get fullRefund => code == 'devolucao_total';
  factory OrderCancel.fromJson(Map<String, dynamic> j) => OrderCancel(by: _s(j['by']), code: _s(j['code']), reason: _s(j['reason']), note: _s(j['note']), at: _s(j['at']));
}

/// Uma devolução ou solicitação de devolução, como o cliente a vê ("falhou" chega como "processando").
class CustomerRefund {
  CustomerRefund({required this.id, required this.kind, required this.status, required this.label, required this.reason, required this.requestedAmount,
      this.approvedAmount, this.decisionNote = '', this.decisionReason = '', this.photos = 0, this.createdAt = '', this.refundedAt});
  final int id, photos;
  final String kind, status, label, reason, decisionNote, decisionReason, createdAt;
  final double requestedAmount;
  final double? approvedAmount;
  final String? refundedAt; // quando o Pix voltou
  bool get requested => kind == 'solicitado';
  factory CustomerRefund.fromJson(Map<String, dynamic> j) => CustomerRefund(
        id: _i(j['id'])!, kind: _s(j['kind']), status: _s(j['status']), label: _s(j['label']), reason: _s(j['reason']), requestedAmount: _d(j['requested_amount']),
        approvedAmount: j['approved_amount'] == null ? null : _d(j['approved_amount']), decisionNote: _s(j['decision_note']), decisionReason: _s(j['decision_reason']),
        photos: _i(j['photos']) ?? 0, createdAt: _s(j['created_at']), refundedAt: j['refunded_at'] as String?,
      );
}

class ShopOrder {
  ShopOrder({
    required this.id, required this.code, this.pickupAddress = '', required this.createdAt, required this.scheduledFor, required this.timeWindow, required this.fulfillment,
    required this.street, required this.number, required this.complement, required this.district, required this.city,
    required this.paymentMethod, required this.notes, required this.total, required this.items, required this.status,
    this.paid = false, this.canCancel = false, this.paymentState = '', this.refundedTotal = 0, this.cancelledBy = '', this.cancelReason = '',
    this.refundMode = 'nenhum', this.refundMax = 0, this.refundPhotosRequired = false, this.refundDeadline, this.refundWhyNot = '', this.refunds = const [], this.cancel, this.hasPix = false, this.pixExpired = false, this.pixExpiresIn = 0, this.pixCode = '', this.pixQrBase64 = '',
  });
  final int id; // interno: usado nas rotas
  final int code; // o número que o cliente vê (4 ou 5 dígitos, aleatório)
  final String pickupAddress; // endereço de retirada da loja na época do pedido (vazio se não é retirada)
  final String createdAt, scheduledFor, timeWindow, fulfillment, street, number, complement, district, city, paymentMethod, notes, status;
  final double total;
  final bool paid;
  final String pixCode, pixQrBase64; // Pix copia e cola enquanto aguarda pagamento (vazios quando o Pix venceu)
  final bool canCancel; // o cliente ainda pode cancelar sozinho: a produção não começou (se já pagou, o Pix volta na hora: refundMode == 'automatico')
  final String paymentState, cancelledBy, cancelReason; // pendente | expirado | cancelado | pago | devolucao_parcial | devolvido
  final double refundedTotal;
  final String refundMode; // automatico | solicitacao | nenhum: o que o cliente pode fazer agora sobre devolução (decidido no servidor)
  final double refundMax;
  final bool refundPhotosRequired;
  final String? refundDeadline;
  final String refundWhyNot;
  final List<CustomerRefund> refunds;
  final OrderCancel? cancel;
  final bool hasPix; // há um Pix para este pedido (válido ou vencido)
  final bool pixExpired; // o Pix venceu: é preciso gerar outro
  final int pixExpiresIn; // segundos restantes de validade, no momento em que a lista foi carregada
  final List<ShopOrderItem> items;
  factory ShopOrder.fromJson(Map<String, dynamic> j) => ShopOrder(
        id: _i(j['id'])!, code: _i(j['code']) ?? _i(j['id'])!, pickupAddress: _s(j['pickup_address']), createdAt: _s(j['created_at']), scheduledFor: _s(j['scheduled_for']), timeWindow: _s(j['time_window']),
        fulfillment: _s(j['fulfillment']), street: _s(j['street']), number: _s(j['number']), complement: _s(j['complement']),
        district: _s(j['district']), city: _s(j['city']), paymentMethod: _s(j['payment_method']), notes: _s(j['notes']), total: _d(j['total']),
        status: _s(j['status']), paid: j['paid'] == true, canCancel: j['can_cancel'] == true, paymentState: _s(j['payment_state']), refundedTotal: _d(j['refunded_total']),
        cancelledBy: _s(j['cancelled_by']), cancelReason: _s(j['cancel_reason']),
        refundMode: _s((j['refund'] as Map?)?['mode']).isEmpty ? 'nenhum' : _s((j['refund'] as Map?)?['mode']), refundMax: _d((j['refund'] as Map?)?['max_amount']),
        refundPhotosRequired: (j['refund'] as Map?)?['photos_required'] == true, refundDeadline: (j['refund'] as Map?)?['deadline'] as String?, refundWhyNot: _s((j['refund'] as Map?)?['why_not']),
        refunds: [for (final e in (j['refunds'] as List? ?? const [])) CustomerRefund.fromJson(e as Map<String, dynamic>)],
        cancel: j['cancel'] is Map ? OrderCancel.fromJson(Map<String, dynamic>.from(j['cancel'] as Map)) : null,
        hasPix: j['pix'] is Map, pixExpired: (j['pix'] as Map?)?['expired'] == true, pixExpiresIn: _i((j['pix'] as Map?)?['expires_in']) ?? 0,
        pixCode: _s((j['pix'] as Map?)?['code']), pixQrBase64: _s((j['pix'] as Map?)?['qr_base64']),
        items: [for (final e in j['items'] as List) ShopOrderItem(id: _s(e['id']), name: _s(e['name']), qty: _i(e['qty']) ?? 0, unitPrice: _d(e['unit_price']))],
      );
}

/// Endereço de retirada guardado pela confeiteira (só um fica ativo: o que o cliente vê).
class PickupAddress {
  PickupAddress({required this.id, required this.label, required this.cep, required this.street, required this.number, required this.complement,
      required this.district, required this.city, required this.instructions, required this.isActive, required this.address});
  final int id;
  final String label, cep, street, number, complement, district, city, instructions;
  final bool isActive;
  final String address; // "Rua, 12 (compl) - Bairro, Cidade", formatado pelo servidor
  factory PickupAddress.fromJson(Map<String, dynamic> j) => PickupAddress(
        id: _i(j['id'])!, label: _s(j['label']), cep: _s(j['cep']), street: _s(j['street']), number: _s(j['number']), complement: _s(j['complement']),
        district: _s(j['district']), city: _s(j['city']), instructions: _s(j['instructions']), isActive: j['is_active'] == true, address: _s(j['address']),
      );
}

/// Endereço de retirada atual da loja (o que o cliente vê ao pedir). Vazio = a confeiteira ainda não escolheu um.
class PickupInfo {
  PickupInfo({this.label = '', this.address = '', this.instructions = ''});
  final String label, address, instructions;
  bool get isEmpty => address.isEmpty;
  factory PickupInfo.fromJson(Map<String, dynamic> j) => PickupInfo(label: _s(j['label']), address: _s(j['address']), instructions: _s(j['instructions']));
}

/// Alerta de margem/preço depois de um ingrediente mudar de preço.
class PriceAlert {
  PriceAlert({required this.id, required this.recipeId, required this.recipe, required this.kind, required this.before, required this.after, this.suggested, required this.cause});
  final int id, recipeId;
  final String recipe, kind, cause; // kind: margem | preco
  final double before, after;
  final double? suggested;
  factory PriceAlert.fromJson(Map<String, dynamic> j) => PriceAlert(
        id: _i(j['id'])!, recipeId: _i(j['recipe_id'])!, recipe: _s(j['recipe']), kind: _s(j['kind']), before: _d(j['before_value']), after: _d(j['after_value']),
        suggested: j['suggested'] == null ? null : _d(j['suggested']), cause: _s(j['cause']),
      );
}
