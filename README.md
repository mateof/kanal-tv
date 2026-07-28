# Kanal

Reproductor de televisión para **Android TV, Google TV y Fire TV**, escrito en Kotlin
con Jetpack Compose. Se conecta a paneles **Xtream Codes** (incluido
[Dispatcharr](https://github.com/Dispatcharr/Dispatcharr)) y a **listas M3U/M3U8**, con
guía de programación **XMLTV**.

> Todos tus canales.

## Qué hace

- **TV en directo** con lista por categorías, favoritos, guía *ahora / después* y
  **vista previa** del canal enfocado sin salir de la lista.
- **Películas y series** con fichas, temporadas, episodios y *continuar viendo*.
- **Guía XMLTV** descargada y emparejada por `tvg-id` o, si el proveedor no lo manda,
  por nombre de canal.
- **Repeticiones (catch-up)** en los paneles Xtream que exponen `tv_archive`.
- **Buscador** sobre canales, películas y series.
- **Registro descargable** con todo lo que hace la app, para diagnosticar fallos.
- **Actualización automática**: comprueba las releases de este repo y descarga e
  instala el APK desde la propia aplicación.

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
