package com.shashank.ghostly

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.RemoteViews

/**
 * A live-ish home screen widget: his current face and a one-line status, without needing the app
 * or the overlay open. Widgets can't run a custom animated View, so this renders one still frame
 * of the same [GhostView] used everywhere else into a bitmap. The system refreshes it at most every
 * 30 minutes on its own (Android's floor for [AppWidgetProviderInfo.updatePeriodMillis]); [refreshAll]
 * pushes a fresh frame immediately whenever his mood actually changes.
 */
class GhostlyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateOne(context, appWidgetManager, it) }
    }

    companion object {
        /** Call whenever his mood/state changes, so any pinned widget stays honest. Cheap no-op if
         *  nobody has actually added the widget. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, GhostlyWidgetProvider::class.java))
            ids.forEach { updateOne(context, manager, it) }
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_ghostly)
            views.setImageViewBitmap(R.id.widget_ghost_image, renderBitmap(context))
            views.setTextViewText(R.id.widget_status_text, statusText(context))

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)

            runCatching { manager.updateAppWidget(id, views) }
        }

        private fun statusText(context: Context): String {
            val name = Prefs.name(context) ?: Prefs.species(context).label
            val s = Emotions.snapshot(context)
            return when {
                s.body.sleeping -> "$name is napping"
                s.mood == Mood.ANGRY -> "$name is upset"
                s.mood == Mood.SAD -> "$name is a little down"
                else -> "$name is content"
            }
        }

        /** Renders a still GhostView — off-screen, never attached — to a small bitmap. */
        private fun renderBitmap(context: Context): Bitmap {
            val sizePx = (72 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val view = GhostView(context)
            view.species = Prefs.species(context)
            val s = Emotions.snapshot(context)
            view.setMood(s.mood, s.body.sleeping)

            val spec = View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)
            view.measure(spec, spec)
            view.layout(0, 0, sizePx, sizePx)

            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            return bitmap
        }
    }
}
