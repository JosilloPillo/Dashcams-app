# Dashcams España — la app

Convierte un móvil viejo en una dashcam. Gratis, sin cuentas y sin enviar nada a ningún servidor:
los vídeos se quedan en el móvil.

## Versión 0.6 (Alpha)

- Empieza a grabar sola al abrirla, en tramos de 1 minuto que se borran solos (bucle de ~3 GB).
- **GUARDAR** (o cualquier tecla de volumen, o un mando Bluetooth de selfie): protege el minuto
  anterior, el actual y el siguiente.
- **Pantalla negra**: tapa la pantalla y baja el brillo para que el móvil se caliente menos. Sigue grabando.
- Temperatura y batería en pantalla. A 50 °C para de grabar y vuelve a empezar sola por debajo de 42 °C.
- **Clips**: ver, compartir o borrar los clips guardados. Al compartir se puede enviar el evento
  directamente a dashcams.es (mismo formulario y condiciones que la web) o a cualquier otra app.
- Usa la cámara más angular del móvil (o el zoom mínimo, si el móvil junta varias lentes).
- Vista de cámara a pantalla completa (16:9, igual que el vídeo).
- **Datos dentro del vídeo**: fecha y hora siempre; velocidad, coordenadas GPS y escudo + «dashcams.es»
  se pueden quitar en Ajustes. El GPS solo se usa para esto y no sale del móvil.
- **Segundo plano**: si sales de la app sigue grabando, con una notificación fija y botón «Guardar clip».
  Con Atrás se para todo. Se puede desactivar en Ajustes.
- **Ajustes**: sonido, pitido al guardar, ver el encuadre completo, cámara de carretera, del interior
  o **las dos a la vez** (con aviso de privacidad) y control por voz.
- **Dos cámaras a la vez** en los móviles que lo permiten: un solo vídeo con la carretera de fondo y el
  interior en una ventanita arriba. Si el móvil no puede, graba solo la carretera y lo avisa.
- **Control por voz** opcional y sin internet (Vosk): di la frase elegida en Ajustes («Grabación de emergencia», «Código rojo» o «Alerta, alerta»), sola y con una pausa. El reconocedor de español
  (39 MB) se descarga solo al activarlo.
- **Clips** con dos pestañas: *Eventos* (lo guardado) y *Últimos minutos* (la grabación en bucle, que se
  borra sola), desde donde se puede guardar un tramo.
- Graba con sonido; se puede apagar con el botón "Sonido" (el cambio es inmediato). Internet solo se usa al enviar un clip a la web.

Diseño según la guía de estilo de los vídeos y la web: azul marino, degradado amarillo, Barlow Condensed y
Public Sans (licencia OFL, ver OFL-*.txt), kicker con punto REC, tarjetas blancas con sombra dura y carriles.

Mínimo Android 8.

## Cómo conseguir el APK

Cada vez que se sube código a `main`, GitHub compila la app solo:

1. En el repositorio, entra en **Releases** (a la derecha) y abre la **Alpha** más reciente.
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
