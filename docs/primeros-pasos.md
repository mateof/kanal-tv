# Primeros pasos

## Instalación

Descargar el APK de la [última release](https://github.com/mateof/kanal-tv/releases/latest) e
instalarlo por cualquiera de estas vías:

- **Downloader** o el navegador del aparato, indicando la URL del APK.
- **`adb install kanal-x.y.z.apk`** desde un equipo en la misma red.
- El **gestor de archivos** del aparato, si permite instalar desde orígenes desconocidos.

Una vez instalada, la aplicación avisa de las versiones nuevas y las instala por sí misma.

**Requisitos**: Android 6.0 (API 23) o superior.

## Alta de la fuente

Al iniciarse por primera vez, Kanal solicita una fuente. Admite dos tipos.

### Xtream Codes y Dispatcharr

| Campo | Descripción |
| --- | --- |
| Nombre | Etiqueta para identificar la fuente |
| URL del servidor | `http://host:puerto` |
| Usuario y contraseña | Credenciales del panel |
| URL de la guía | Opcional. En blanco, se utiliza el `xmltv.php` del propio servidor |
| User-Agent | Opcional. Algunos proveedores exigen uno concreto |

No es necesario depurar la URL: si incluye `/player_api.php` o parámetros de consulta, Kanal
los descarta y conserva la base.

### Lista M3U

| Campo | Descripción |
| --- | --- |
| Nombre | Etiqueta para identificar la fuente |
| URL de la lista | Dirección del `.m3u` o `.m3u8` |
| URL de la guía XMLTV | Opcional. Si la lista declara `url-tvg`, se utiliza ese valor |
| User-Agent | Opcional |

De cada entrada `#EXTINF` se leen los atributos `tvg-id`, `tvg-name`, `tvg-logo`,
`group-title` y `catchup-days`.

La clasificación entre canales, películas y series se realiza según la ruta de la URL
(`/live/`, `/movie/`, `/series/`). Los episodios se agrupan en series a partir del patrón
`S01 E02` presente en el nombre.

### Comprobación previa

**Probar conexión** valida la fuente sin guardarla. En Xtream Codes autentica y devuelve el
usuario, el estado de la cuenta y el número de conexiones activas. En M3U comprueba que la
lista responde.

## Sincronización

**Guardar y empezar** descarga el catálogo y la guía. La primera sincronización es la más
larga. Posteriormente:

- La **sincronización automática** refresca el catálogo al abrir la aplicación con la
  periodicidad configurada en Ajustes.
- **Sincronizar todo** y **Actualizar sólo la guía** permiten forzar una actualización.

La base de datos local funciona como caché: cada sincronización reemplaza el contenido
anterior de esa fuente. Los favoritos y el historial se almacenan aparte y no se ven
afectados.

## Múltiples fuentes

Es posible dar de alta varias fuentes y alternar entre ellas desde Ajustes mediante **Usar**.
Sólo una permanece activa, y el contenido mostrado corresponde siempre a esa fuente.
