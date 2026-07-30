# Primeros pasos

## Instalar

Descarga el APK de la [última release](https://github.com/mateof/kanal-tv/releases/latest) e
instálalo por cualquiera de estas vías:

- **Downloader** (o el navegador de la tele) apuntando a la URL del APK.
- **`adb install kanal-x.y.z.apk`** desde un ordenador en la misma red.
- El **gestor de archivos** de la tele, si permite instalar de orígenes desconocidos.

A partir de ahí la propia app avisa de las versiones nuevas y las instala sola.

Requisitos: **Android 6.0 (API 23)** o superior, que cubre los Fire TV Stick de 2015-2018.

> Si la instalación falla con un «Aplicación no instalada» sin más explicación, lo primero
> que hay que descartar es el **espacio libre**: en las teles con 8 GB se llena antes de lo
> que parece. Para ver el motivo real: `adb install -r kanal.apk` da el
> `INSTALL_FAILED_...` concreto.

## Añadir la fuente

Al abrirla por primera vez, Kanal pide una fuente. Hay dos tipos.

### Xtream Codes (y Dispatcharr)

| Campo | Qué poner |
| --- | --- |
| Nombre | El que quieras, sólo es para distinguirla |
| URL del servidor | `http://host:puerto` — pégala tal cual |
| Usuario y contraseña | Los del panel |
| URL de la guía | Opcional; vacía usa el `xmltv.php` del propio servidor |
| User-Agent | Opcional; algunos proveedores sólo responden a uno concreto |

No hace falta limpiar la URL: si pegas `http://host:8080/player_api.php?username=…`, Kanal
recorta lo que sobra y se queda con la base.

### Lista M3U

| Campo | Qué poner |
| --- | --- |
| Nombre | El que quieras |
| URL de la lista | El `.m3u` o `.m3u8` |
| URL de la guía XMLTV | Opcional; si la lista anuncia `url-tvg`, Kanal la coge de ahí |
| User-Agent | Opcional |

De cada `#EXTINF` se leen `tvg-id`, `tvg-name`, `tvg-logo`, `group-title` y `catchup-days`.
Los canales, las películas y las series se distinguen por la ruta de la URL (`/live/`,
`/movie/`, `/series/`), y los episodios se agrupan en series por el patrón `S01 E02` del
nombre.

### Probar antes de guardar

**Probar conexión** hace la comprobación sin guardar nada: en Xtream autentica y dice con qué
usuario ha entrado y cuántas conexiones tienes abiertas; en M3U sólo comprueba que la lista
responde.

## Sincronizar

**Guardar y empezar** descarga el catálogo y la guía. La primera vez es la más lenta, porque
baja todo. Después:

- **Sincronización automática** (en Ajustes) refresca el catálogo al abrir la app, cada 6, 12,
  24 o 48 horas, o nunca.
- **Sincronizar todo** y **Actualizar sólo la guía** fuerzan una pasada cuando quieras.

La base de datos local es **sólo caché**: cada sincronización reemplaza el contenido anterior
de esa fuente, así que un canal que el proveedor quite desaparece también aquí. Los
favoritos y el historial no viven ahí y no se pierden.

## Varias fuentes

Puedes tener varias y cambiar de una a otra en Ajustes con **Usar**. Sólo hay una activa a la
vez, y el contenido que se ve es siempre el de esa.
