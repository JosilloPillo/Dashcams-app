# Pendiente para próximas versiones

## Datos sobreimpresos en el vídeo (pedido el 19/11/2026)

Que queden grabados dentro del propio clip (no solo en pantalla):

| Dato | ¿Se puede desactivar en Ajustes? |
| --- | --- |
| Fecha y hora | **No**, siempre visible |
| Coordenadas GPS (latitud, longitud) | Sí |
| Velocidad (km/h, por GPS) | Sí |
| Escudo + «dashcams.es» | Sí |

Notas técnicas:
- Grabarlo dentro del vídeo con `OverlayEffect` de CameraX (librería `camera-effects`), con el estilo de la guía
  (Public Sans / Barlow, pastilla translúcida).
- Coordenadas y velocidad necesitan permiso de ubicación (`ACCESS_FINE_LOCATION`); se pide solo si se activan.
  Siguen sin salir del móvil.
- Velocidad: la del GPS; avisar de que es orientativa (las apps de la competencia reciben quejas por velocidad
  imprecisa).

## Ideas sacadas de las reseñas de Droid Dashcam

- Empezar a grabar sola al conectar el cargador o el Bluetooth del coche (es de lo más valorado).
- Seguir grabando en segundo plano (servicio en primer plano) y guiar al usuario para quitar la optimización
  de batería (Xiaomi, Huawei, Oppo... matan las apps).

## Ya apuntado antes

- Grabar carretera e interior a la vez en móviles que lo permitan (Ajustes ya detecta si se puede).
- Versión para iPhone (necesita cuenta de desarrollador de Apple).
