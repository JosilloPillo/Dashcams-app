package es.dashcams.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes

/**
 * Iconos de la app con la bandera de cada comunidad.
 *
 * Cada icono es un "alias" de la pantalla principal declarado en el manifiesto
 * (es.dashcams.app.Icono_<id>). Solo hay uno activo a la vez: ese es el que sale en el móvil.
 */
object AppIcons {

    data class Icon(val id: String, val name: String, @DrawableRes val shield: Int)

    val all = listOf(
        Icon("espana", "España", R.drawable.escudo),
        Icon("andalucia", "Andalucía", R.drawable.escudo_andalucia),
        Icon("aragon", "Aragón", R.drawable.escudo_aragon),
        Icon("asturias", "Asturias", R.drawable.escudo_asturias),
        Icon("baleares", "Illes Balears", R.drawable.escudo_baleares),
        Icon("canarias", "Canarias", R.drawable.escudo_canarias),
        Icon("cantabria", "Cantabria", R.drawable.escudo_cantabria),
        Icon("castilla_leon", "Castilla y León", R.drawable.escudo_castilla_leon),
        Icon("castilla_mancha", "Castilla-La Mancha", R.drawable.escudo_castilla_mancha),
        Icon("catalunya", "Catalunya", R.drawable.escudo_catalunya),
        Icon("valencia", "C. Valenciana", R.drawable.escudo_valencia),
        Icon("extremadura", "Extremadura", R.drawable.escudo_extremadura),
        Icon("galicia", "Galicia", R.drawable.escudo_galicia),
        Icon("madrid", "Madrid", R.drawable.escudo_madrid),
        Icon("murcia", "Murcia", R.drawable.escudo_murcia),
        Icon("navarra", "Navarra", R.drawable.escudo_navarra),
        Icon("pais_vasco", "País Vasco", R.drawable.escudo_pais_vasco),
        Icon("rioja", "La Rioja", R.drawable.escudo_rioja),
        Icon("ceuta", "Ceuta", R.drawable.escudo_ceuta),
        Icon("melilla", "Melilla", R.drawable.escudo_melilla),
    )

    private fun component(context: Context, id: String) =
        ComponentName(context.packageName, "es.dashcams.app.Icono_$id")

    /** El icono que se ve ahora en el móvil. */
    fun current(context: Context): Icon {
        val pm = context.packageManager
        for (icon in all) {
            val state = pm.getComponentEnabledSetting(component(context, icon.id))
            val enabled = when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon.id == "espana" // el único activo de serie
                else -> false
            }
            if (enabled) return icon
        }
        return all.first()
    }

    /**
     * Cambia el icono. Primero activa el nuevo y luego apaga los demás, para que nunca
     * se quede la app sin icono.
     */
    fun apply(context: Context, icon: Icon) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            component(context, icon.id),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        for (other in all) {
            if (other.id == icon.id) continue
            pm.setComponentEnabledSetting(
                component(context, other.id),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
