package es.dashcams.app

import android.content.Context
import androidx.annotation.DrawableRes

/**
 * Banners de Dashcams España que salen al abrir la app, uno distinto cada vez (van rotando).
 * Es la única "publicidad" de la app y solo es de la web, el canal y la tienda.
 */
object Promos {

    data class Promo(
        val id: String,
        @DrawableRes val image: Int,
        val cta: String,
        /** Enlace que se abre, o null para abrir Clips (enviar un clip desde la app). */
        val url: String?
    )

    val all = listOf(
        Promo("recomendador", R.drawable.promo_recomendador, "Hacer el test", "https://dashcams.es/recomendador"),
        Promo("clips", R.drawable.promo_clips, "Enviar un clip", null),
        Promo("youtube", R.drawable.promo_youtube, "Ver el canal", Links.YOUTUBE),
        Promo("comparador", R.drawable.promo_comparador, "Ver dashcams", "https://dashcams.es/mejores-dashcams"),
        Promo("tienda", R.drawable.promo_tienda, "Ir a la tienda", Links.SHOP),
        Promo("blog", R.drawable.promo_blog, "Leer el blog", "https://dashcams.es/blog"),
    )

    /** El siguiente banner de la rotación. */
    fun next(context: Context): Promo {
        val prefs = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
        val i = prefs.getInt(KEY_INDEX, 0) % all.size
        prefs.edit().putInt(KEY_INDEX, i + 1).apply()
        return all[i]
    }

    private const val KEY_INDEX = "banner_siguiente"
    const val SHOW_MS = 10_000L
}
