# Dashcams España — la app

Convierte un móvil viejo en una dashcam. Gratis, sin cuentas y sin enviar nada a ningún servidor:
los vídeos se quedan en el móvil.

## Versión 0.3 (con el diseño de Dashcams España)

- Empieza a grabar sola al abrirla, en tramos de 1 minuto que se borran solos (bucle de ~3 GB).
- **GUARDAR** (o cualquier tecla de volumen, o un mando Bluetooth de selfie): protege el minuto
  anterior, el actual y el siguiente.
- **Pantalla negra**: tapa la pantalla y baja el brillo para que el móvil se caliente menos. Sigue grabando.
- Temperatura y batería en pantalla. A 50 °C para de grabar y vuelve a empezar sola por debajo de 42 °C.
- **Clips**: ver, compartir o borrar los clips guardados. Al compartir se puede enviar el evento
  directamente a dashcams.es (mismo formulario y condiciones que la web) o a cualquier otra app.
- Usa la cámara trasera más angular del móvil (o el zoom mínimo, si el móvil junta varias lentes).
- Graba con sonido; se puede apagar con el botón "Sonido" (el cambio es inmediato). Internet solo se usa al enviar un clip a la web.

Diseño según la guía de estilo de los vídeos y la web: azul marino, degradado amarillo, Barlow Condensed y
Public Sans (licencia OFL, ver OFL-*.txt), kicker con punto REC, tarjetas blancas con sombra dura y carriles.

Mínimo Android 8.

## Cómo conseguir el APK

Cada vez que se sube código a `main`, GitHub compila la app solo:

1. En el repositorio, entra en **Releases** (a la derecha) y abre la **Versión de prueba** más reciente.
2. Descarga el fichero `.apk` desde el móvil (tienes que tener la sesión de GitHub abierta en el navegador,
   porque el repositorio es privado).
3. Ábrelo e instálalo. Android te pedirá permitir "instalar apps de fuentes desconocidas" para el navegador.
4. Las versiones nuevas se instalan encima sin perder los clips.

Si una compilación falla, aparece en rojo en la pestaña **Actions**.

## Qué probar en el coche

1. ¿Graba seguido durante un viaje de más de 30 minutos sin cortarse?
2. ¿A qué temperatura llega el móvil (cargando, al sol, con y sin pantalla negra)?
3. Pulsa GUARDAR y revisa en Clips que salen las 3 partes y se ven bien.
4. ¿Se ve bien de noche?
5. Si la app falla o se cierra: modelo del móvil, versión de Android y qué estabas haciendo.
