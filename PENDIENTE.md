# Pendiente para próximas versiones

## Hecho en la 0.9 (23/11/2026)

- Segundo plano: pide permiso de notificaciones, solo sigue grabando fuera si Android ha aceptado el servicio y, si no, avisa con el motivo.
- Abrir Clips ya no para la grabación.
- Botón flotante (escudo) sobre otras apps: tocar guarda, mantener abre la app, arrastrar lo mueve. Desde la 0.9.1 viene apagado y al activarlo pide el permiso.
- Dos cámaras en dos vídeos separados (_F y _I) a 720p, en vez de uno combinado.
- Interior en 4:3 para no recortar el sensor de la cámara frontal.
- Eventos: solo el minuto del aviso, más el anterior si cae en sus primeros 20 s o el siguiente si cae en los últimos 20 s.

## Hecho en la 0.8 (23/11/2026)

- El escudo y «dashcams.es» salen siempre en el vídeo (ya no se pueden quitar).
- Pitido al guardar por el canal de alarma (antes no se oía en muchos móviles).
- Cada clip lleva la letra de su cámara: F carretera, I interior, FI las dos.
- 0.8.1: nombres con fecha a la española y prefijo de la web: dashcams.es_23-11-2026_13-15-00_F.mp4.
- Dos cámaras a 720p (lo máximo que Android garantiza con dos cámaras) y el motivo del fallo en Ajustes.

## Hecho en la 0.7 (19/11/2026)

- Icono de la app a elegir: España + 17 comunidades + Ceuta y Melilla (originales en marca/comunidades/).

## Hecho en la 0.6 (19/11/2026)

- Carretera e interior a la vez en un solo vídeo (interior en ventanita), en móviles que lo permiten.
  Por probar en un móvil real: posición de la ventanita y si los datos del vídeo salen también en este modo.

## Hecho en la 0.5 (19/11/2026)

- Datos dentro del vídeo: fecha y hora (siempre), velocidad, coordenadas y escudo + «dashcams.es»
  (estos tres, desactivables en Ajustes).
- Seguir grabando con la app en segundo plano (notificación con «Guardar clip»), desactivable en Ajustes,
  con botón para quitar la optimización de batería.

## Descartado

- Empezar a grabar al conectar el cargador o el Bluetooth: se graba al abrir la app y ya.

## Pendiente

- Pasar a Android 16 (targetSdk 36) y actualizar el plugin de Android: obligatorio para Google Play.
- Versión para iPhone (necesita cuenta de desarrollador de Apple).
- Antes de publicar en Google Play: clave de firma propia, declarar en la consola el uso de cámara,
  micrófono y ubicación en segundo plano, y la ficha de «Seguridad de los datos».
