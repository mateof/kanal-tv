# Televisión en directo

## Lista de canales

![TV en directo](img/tv-en-directo.png)

La franja superior contiene las categorías del proveedor, junto con **Todos los canales** y
**Favoritos**. La columna izquierda muestra cada canal con su logotipo, el programa en
emisión y una barra de progreso. El panel derecho corresponde al canal enfocado.

### Vista previa

Transcurrido un breve intervalo sobre un canal, su emisión comienza a reproducirse en el
panel derecho. El retardo evita abrir una conexión por cada canal al recorrer la lista con
rapidez. La función puede desactivarse en
[Ajustes](ajustes.md#vista-previa-en-la-lista-de-canales).

Bajo la vista previa aparecen el programa actual con su descripción, el siguiente, y los
botones **Ver ahora** y **Añadir a favoritos**. Más abajo, la programación del día con
selector de fecha.

### Recordar el último canal

Con este ajuste activo, al salir de un canal con **ATRÁS** la emisión continúa en la vista
previa. Desde la lista:

- **ATRÁS** devuelve el canal a pantalla completa.
- **ATRÁS dos veces consecutivas** abandona la lista.

## Reproductor

![Reproductor](img/reproductor.png)

La información en pantalla aparece al iniciar la reproducción y con cualquier pulsación, y se
oculta automáticamente. Incluye logotipo y nombre del canal, posición en la lista, programa
en emisión con su franja horaria y progreso, descripción y programa siguiente. Los títulos
que exceden el ancho disponible se desplazan horizontalmente.

### Manejo con mando

| Tecla | Emisión en directo | Películas y series |
| --- | --- | --- |
| **Arriba / Abajo** | Canal siguiente y anterior | — |
| **Izquierda / Derecha** | — | Retroceso y avance de 10 segundos |
| **OK** | Abre los controles | Abre los controles |
| **ATRÁS** | Sale de la reproducción | Sale de la reproducción |

Con los controles abiertos, **izquierda** y **derecha** desplazan el foco entre los botones y
**ATRÁS** regresa al vídeo. La línea inferior recuerda esta correspondencia, ya que muchos
mandos de televisión carecen de tecla de menú.

### Desplazarse por la reproducción

En películas y series la barra de progreso permite ir a cualquier punto:

- **Con el mando**: cuando la barra tiene el foco, izquierda y derecha la mueven. El paso
  parte de 10 segundos y aumenta mientras se mantiene pulsada la tecla, hasta un máximo de
  2 minutos.
- **Táctil**: una pulsación sobre un punto salta a él; el arrastre recorre la reproducción.

Mientras la barra se mueve se muestra el punto de destino y su diferencia respecto a la
posición actual. El salto se aplica al soltar, al pulsar OK o tras un instante sin
movimiento, de modo que recorrer una película no provoca una recarga por cada pulsación.

Los botones **10 s** siguen disponibles para ajustes finos sin necesidad de enfocar la barra.

### Gestos táctiles

En móvil y tablet, sobre la imagen:

| Gesto | Efecto |
| --- | --- |
| Tocar | Muestra los controles; con algo visible, lo oculta |
| Deslizar a izquierda o derecha | Cierra el vídeo y vuelve a la lista |
| Deslizar hacia arriba | Pantalla completa en horizontal, sin barras del sistema |
| Deslizar hacia abajo | Reduce a ventana flotante |

La orientación y las barras del sistema vuelven a su estado anterior al salir de la
reproducción.

### Controles

- **Ficha** — descripción completa del programa.
- **Guía** — programación del canal sin abandonar la reproducción.
- **Ajustar** — alterna entre ajustar, zoom y estirar, para material que no se emite en 16:9.
- **Audio y subtítulos** — pistas disponibles en la emisión, con idioma y códec.

![Ficha de programa](img/ficha-programa.png)

### Subtítulos

Los subtítulos permanecen **desactivados** hasta que se seleccionan de forma explícita, aunque
la emisión los incluya. La elección se conserva entre reproducciones y entre sesiones: una vez
activados siguen activos hasta que se elija **Sin subtítulos**.

### Ventana pequeña

El control **Ventana pequeña** reduce la reproducción a una ventana flotante y deja el resto
del aparato libre. También ocurre al salir de la aplicación mientras se reproduce.

La emisión no se interrumpe: la conexión con el proveedor se mantiene abierta, de modo que no
cuenta como una reproducción nueva.

Dentro de la ventana no se muestra ningún control de la aplicación. El sistema aporta los
suyos al tocarla: cerrar, ajustes y volver a pantalla completa. La ventana se puede mover y
redimensionar con los gestos habituales del sistema.

Requiere Android 8.0 o superior; en versiones anteriores el control no aparece.

### Enviar a otro aparato

Hay dos formas de hacerlo:

- **Desde la lista**, con una pulsación larga sobre un canal. Es la recomendada: la emisión
  no llega a abrirse en este aparato, de modo que sólo se pide una vez al proveedor.
- **Desde el reproductor**, con el control **Enviar a…**. La reproducción local se detiene
  antes de dar la orden; **Traer de vuelta** la recupera.

Ambas buscan reproductores **DLNA/UPnP** en la red local y ofrecen la lista.

Consideraciones:

- El aparato debe estar encendido, en la misma red y admitir DLNA. En muchos televisores esta
  función aparece como «renderizador multimedia» y viene desactivada de fábrica.
- Es el aparato de destino quien descarga la emisión, no Kanal. Cuenta por tanto como una
  conexión adicional frente al proveedor, y le aplican sus restricciones de agente y de
  número de conexiones simultáneas.
- Si el televisor no aparece en la lista, puede añadirse indicando su dirección IP o la URL
  de su descripción UPnP. Los aparatos añadidos así se conservan entre sesiones.
- Con cuentas que permiten una sola conexión simultánea no es posible ver el mismo contenido
  en el televisor y en este aparato a la vez.

No requiere Chromecast ni servicios de Google, por lo que funciona también en aparatos sin
ellos.

### Errores de reproducción

Si una emisión no se inicia, Kanal prueba otros contenedores para el mismo canal antes de
mostrar un error, ya que algunos paneles anuncian un formato y sirven otro. Cuando ninguna
alternativa funciona, se muestra un mensaje descriptivo con las opciones **Reintentar** y
**Volver**.

Con [«Aguantar cortes del servidor»](ajustes.md#aguantar-cortes-del-servidor) activado, la
aplicación reconecta automáticamente e indica «Reconectando…» sin abandonar el canal.

## Guía de programación

![Guía](img/guia.png)

Presenta todos los canales de forma simultánea. Los bloques se dimensionan según la duración
real de cada programa. La franja horaria se desplaza con **−2 h**, **Ahora** y **+2 h**, y el
listado admite filtrado por categoría.

Al seleccionar un programa se abre su ficha; el cambio de canal se realiza desde el icono
correspondiente dentro de la ficha.

Cada carga se limita a 150 canales y 6 horas para mantener el desplazamiento fluido en
aparatos de recursos limitados.

### Repeticiones

En los paneles Xtream Codes que exponen `tv_archive`, los programas ya emitidos ofrecen la
opción **Ver repetición**. Los días disponibles se indican en la ficha del canal.
