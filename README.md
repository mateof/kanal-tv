# Kanal

Reproductor de televisión para **Android TV, Google TV y Fire TV**, escrito en Kotlin
con Jetpack Compose. Se conecta a paneles **Xtream Codes** (incluido
[Dispatcharr](https://github.com/Dispatcharr/Dispatcharr)) y a **listas M3U/M3U8**, con
guía de programación **XMLTV**.

> Todos tus canales.

![TV en directo](docs/img/tv-en-directo.png)

<table>
  <tr>
    <td><img src="docs/img/guia.png" alt="Guía"></td>
    <td><img src="docs/img/reproductor.png" alt="Reproductor"></td>
  </tr>
  <tr>
    <td><img src="docs/img/inicio.png" alt="Inicio"></td>
    <td><img src="docs/img/peliculas.png" alt="Películas"></td>
  </tr>
</table>

*Capturas hechas con una fuente de demostración inventada, no con un proveedor real.*

## Qué hace

- **TV en directo** con lista por categorías, favoritos, guía *ahora / después* y
  **vista previa** del canal enfocado sin salir de la lista.
- **Guía XMLTV** en muro de todos los canales a la vez, con los bloques a escala de
  duración, y por canal con selector de día. Emparejada por `tvg-id` o, si el proveedor
  no lo manda, por nombre de canal.
- **Películas y series** con fichas, temporadas, episodios y *continuar viendo*.
- **Repeticiones (catch-up)** en los paneles Xtream que exponen `tv_archive`.
- **Buscador** sobre canales, películas y series, insensible a acentos.
- **Galego, castellano e inglés**. Por defecto sigue el idioma de la televisión; si no está
  traducido, se muestra en galego.
- **Aguanta cortes**: si la emisión se corta, reconecta sola sin echarte del canal, y prueba
  otros contenedores cuando un canal no arranca.
- **Temporizador de apagado** y aviso de **«¿sigues ahí?»** tras una hora sin tocar el mando,
  para no dejar un canal corriendo toda la noche.
- **Táctil y vertical** en móvil y tablet, como capa añadida: la tele manda.
- **Registro descargable** con todo lo que hace la app, para diagnosticar fallos.
- **Actualización automática**: comprueba las releases de este repo y descarga e
  instala el APK desde la propia aplicación.

## Documentación

En [`docs/`](docs/README.md), con capturas:

- [Primeros pasos](docs/primeros-pasos.md) — instalar, añadir la fuente y sincronizar
- [Televisión en directo](docs/television.md) — lista, guía, reproductor y mando
- [Películas y series](docs/peliculas-y-series.md) — catálogos, fichas y continuar viendo
- [Ajustes](docs/ajustes.md) — todos los ajustes, uno a uno
- [Diagnóstico](docs/diagnostico.md) — registro, actualizaciones y qué hacer si falla

## Compatibilidad

| Fuente | Qué se usa |
| --- | --- |
| Xtream Codes / Dispatcharr | `player_api.php` (`get_live_streams`, `get_vod_streams`, `get_series`, `get_series_info`, `get_short_epg`), `xmltv.php`, `/live/…`, `/movie/…`, `/series/…`, `/timeshift/…` |
| M3U / M3U8 | `#EXTINF` con `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `catchup-days`, `url-tvg` |
| Guía | XMLTV plano o comprimido en gzip |

Kanal es deliberadamente tolerante con lo que devuelven los paneles: los campos se leen
del JSON en crudo, aceptando números como texto, booleanos como `0`/`1`/`"true"` y
colecciones vacías servidas como `{}` en vez de `[]`. Si pegas la URL del panel con
`/player_api.php` o parámetros incluidos, se recorta sola.

## Requisitos

- Android 6.0 (API 23) o superior — cubre los Fire TV Stick de 2015-2018.
- Una fuente Xtream o una lista M3U propia. **Kanal no incluye ni proporciona contenido.**

## Instalación

Descarga el APK de la [última release](https://github.com/mateof/kanal-tv/releases/latest)
e instálalo con *Downloader*, `adb install` o el gestor de archivos de tu tele. A partir
de ahí la propia app avisa de las versiones nuevas.

## Compilar

```bash
export JAVA_HOME="/ruta/a/un/jdk-21"
./gradlew :app:assembleDebug
```

Hace falta un `local.properties` con `sdk.dir` apuntando al SDK de Android.

## Licencia

GPL-3.0. Ver [LICENSE](LICENSE).
