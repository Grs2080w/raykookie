package br.com.kntap.raykookie_gestao

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

// Widget da tela inicial: um toque abre o app direto na Venda Presencial (deep link, sem passar por nenhuma outra tela).
class VendaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(DEEP_LINK), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val views = RemoteViews(context.packageName, R.layout.venda_widget)
        views.setOnClickPendingIntent(R.id.venda_widget_root, pending)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private companion object {
        // o Flutter usa o caminho do link como rota inicial: /painel/venda-presencial
        const val DEEP_LINK = "raykookie://app/painel/venda-presencial"
    }
}
