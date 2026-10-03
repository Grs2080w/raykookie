import 'package:flutter/material.dart';

/// Ícones de categoria: mesmos nomes do web (lucide) apontando para ícones equivalentes do Material.
const categoryIcons = <String, IconData>{
  'wheat': Icons.grass_rounded, 'candy': Icons.bubble_chart_rounded, 'milk': Icons.local_drink_rounded, 'cookie': Icons.cookie_rounded,
  'egg': Icons.egg_rounded, 'droplet': Icons.water_drop_rounded, 'apple': Icons.apple_rounded, 'nut': Icons.eco_rounded,
  'cake-slice': Icons.cake_rounded, 'sparkles': Icons.auto_awesome_rounded, 'flask-conical': Icons.science_rounded, 'wind': Icons.air_rounded,
  'package': Icons.inventory_2_rounded, 'tag': Icons.sell_rounded, 'cake': Icons.bakery_dining_rounded, 'cup-soda': Icons.emoji_food_beverage_rounded,
  'pie-chart': Icons.pie_chart_rounded, 'ice-cream-cone': Icons.icecream_rounded, 'sandwich': Icons.lunch_dining_rounded, 'croissant': Icons.bakery_dining_outlined,
  'coffee': Icons.coffee_rounded, 'gift': Icons.card_giftcard_rounded, 'zap': Icons.bolt_rounded, 'droplets': Icons.water_drop_outlined,
  'flame': Icons.local_fire_department_rounded, 'home': Icons.home_rounded, 'wifi': Icons.wifi_rounded, 'truck': Icons.local_shipping_rounded,
  'megaphone': Icons.campaign_rounded, 'receipt': Icons.receipt_long_rounded, 'wrench': Icons.build_rounded, 'cherry': Icons.local_florist_rounded,
  'citrus': Icons.wb_sunny_rounded, 'grape': Icons.bubble_chart_outlined, 'banana': Icons.nightlight_round, 'carrot': Icons.eco_outlined,
  'beef': Icons.kebab_dining_rounded, 'fish': Icons.set_meal_rounded, 'salad': Icons.spa_outlined, 'pizza': Icons.local_pizza_rounded,
  'soup': Icons.soup_kitchen_rounded, 'utensils': Icons.restaurant_rounded, 'chef-hat': Icons.restaurant_menu_rounded, 'cooking-pot': Icons.outdoor_grill_rounded,
  'ice-cream-bowl': Icons.icecream_outlined, 'beer': Icons.sports_bar_rounded, 'wine': Icons.wine_bar_rounded, 'glass-water': Icons.local_bar_rounded,
  'popcorn': Icons.movie_rounded, 'lollipop': Icons.cookie_outlined, 'leaf': Icons.eco_rounded, 'flower-2': Icons.local_florist_outlined,
  'heart': Icons.favorite_rounded, 'star': Icons.star_rounded, 'crown': Icons.workspace_premium_rounded, 'gem': Icons.diamond_rounded,
  'shopping-bag': Icons.shopping_bag_rounded, 'shopping-cart': Icons.shopping_cart_rounded, 'store': Icons.storefront_rounded, 'scale': Icons.scale_rounded,
  'thermometer': Icons.thermostat_rounded, 'timer': Icons.timer_rounded, 'snowflake': Icons.ac_unit_rounded, 'sun': Icons.light_mode_rounded,
  'moon': Icons.dark_mode_rounded, 'cloud': Icons.cloud_rounded, 'smile': Icons.sentiment_satisfied_rounded, 'party-popper': Icons.celebration_rounded,
  'baby': Icons.child_care_rounded, 'users': Icons.groups_rounded, 'user': Icons.person_rounded, 'briefcase': Icons.work_rounded,
  'wallet': Icons.account_balance_wallet_rounded, 'credit-card': Icons.credit_card_rounded, 'banknote': Icons.payments_rounded, 'percent': Icons.percent_rounded,
  'calendar': Icons.calendar_month_rounded, 'clock': Icons.schedule_rounded, 'box': Icons.inventory_2_outlined, 'boxes': Icons.all_inbox_rounded,
  'archive': Icons.archive_rounded, 'bookmark': Icons.bookmark_rounded, 'palette': Icons.palette_rounded, 'brush': Icons.brush_rounded,
  'scissors': Icons.content_cut_rounded, 'hammer': Icons.hardware_rounded, 'sprout': Icons.spa_rounded, 'trees': Icons.park_rounded,
  'mountain': Icons.terrain_rounded, 'bike': Icons.pedal_bike_rounded, 'car': Icons.directions_car_rounded, 'plane': Icons.flight_rounded,
  'phone': Icons.phone_rounded, 'mail': Icons.mail_rounded, 'printer': Icons.print_rounded,
};

IconData categoryIcon(String name) => categoryIcons[name] ?? Icons.sell_rounded;

/// "#d42a76" → Color
Color hexColor(String hex, [Color fallback = const Color(0xFFE0357A)]) {
  final h = hex.replaceAll('#', '');
  final v = int.tryParse(h.length == 6 ? 'FF$h' : h, radix: 16);
  return v == null ? fallback : Color(v);
}
