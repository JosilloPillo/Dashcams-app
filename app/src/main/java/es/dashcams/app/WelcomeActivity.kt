package es.dashcams.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import es.dashcams.app.databinding.ActivityWelcomeBinding

/**
 * Bienvenida: cómo funciona, qué permisos se piden y por qué, consejos para colocar el móvil
 * y los canales y la tienda de Dashcams España. Sale la primera vez y desde Ajustes.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.title.text = Brand.highlight("Tu móvil, ahora es una dashcam", "dashcam")
        Brand.enter(binding.content)

        binding.btnYoutube.setOnClickListener { open(Links.YOUTUBE) }
        binding.btnInstagram.setOnClickListener { open(Links.INSTAGRAM) }
        binding.btnTiktok.setOnClickListener { open(Links.TIKTOK) }
        binding.btnWeb.setOnClickListener { open(Links.WEB) }
        binding.btnShop.setOnClickListener { open(Links.SHOP) }

        binding.btnStart.text = if (seen(this)) "Volver a la app" else "Empezar a grabar"
        binding.btnStart.setOnClickListener {
            val first = !seen(this)
            getSharedPreferences("ajustes", MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
            if (first) {
                startActivity(Intent(this, MainActivity::class.java).putExtra("desde_bienvenida", true))
            }
            finish()
        }
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val KEY_SEEN = "bienvenida_vista"

        fun seen(context: Context): Boolean =
            context.getSharedPreferences("ajustes", MODE_PRIVATE).getBoolean(KEY_SEEN, false)
    }
}

/** Enlaces de Dashcams España. */
object Links {
    const val YOUTUBE = "https://www.youtube.com/@DashcamsEsp"
    const val INSTAGRAM = "https://www.instagram.com/dashcams.esp"
    const val TIKTOK = "https://www.tiktok.com/@dashcams.esp"
    const val WEB = "https://dashcams.es"
    const val SHOP = "https://tienda.dashcams.es"
}
