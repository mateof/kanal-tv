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

### Favoritos

Una pulsación larga sobre cualquier canal de la lista —el dedo sobre la fila, o la tecla OK
mantenida— abre un menú con **Añadir a favoritos** y **Enviar a…**. La opción cambia de nombre
según el estado, de manera que sirve tanto para marcar como para desmarcar, y la estrella
aparece en la fila en el momento. Los canales marcados se filtran con la pestaña **Favoritos**
del listado.

Lo mismo puede hacerse desde el propio reproductor, en el menú descrito más abajo.

## Reproductor

![Reproductor](img/reproductor.png)

La información en pantalla aparece al iniciar la reproducción y con cualquier pulsación, y se
oculta automáticamente. Incluye logotipo y nombre del canal, posición en la lista, programa
en emisión con su franja horaria y progreso, descripción y programa siguiente. Los títulos
que exceden el ancho disponible se desplazan horizontalmente.

### Manejo con mando

| Tecla | Emisión en directo | Películas y series |
| --- | --- | --- |
| **Arriba / Abajo** | Abren la lista de canales | — |
| **Izquierda / Derecha** | — | Retroceso y avance de 10 segundos |
| **OK** | Con la lista abierta, cambia al canal señalado | Muestra la barra de progreso |
| **OK mantenido** | Abre el menú | Abre el menú |
| **ATRÁS** | Cierra la lista, o sale | Sale de la reproducción |

Con el menú abierto, **arriba** y **abajo** recorren las opciones y **ATRÁS** regresa al vídeo.
La línea inferior recuerda esta correspondencia, ya que muchos mandos de televisión carecen de
tecla de menú; donde exista, **MENÚ** o **INFO** abren lo mismo con una sola pulsación.

### Lista de canales sobre la imagen

Arriba o abajo despliegan una tira de logotipos al pie de la pantalla, con el canal en
emisión señalado en el centro y los demás a ambos lados. **Izquierda** y **derecha** la
recorren; el rótulo superior describe en cada momento el canal señalado —nombre, logotipo,
posición y programación— y lo distingue del que se está viendo resaltando su nombre.

Nada se sintoniza hasta pulsar **OK**, de modo que pasar por encima de diez canales no cuesta
diez conexiones ni interrumpe lo que se está viendo. **ATRÁS** cierra la tira sin tocar nada.

En pantallas táctiles, donde no hay flechas, la tira se abre desde la opción **Canales** del
menú, y basta tocar un logotipo para cambiar a ese canal.

La tira abarca la lista completa, pero solo carga los canales próximos a la posición señalada:
una lista de decenas de miles no cabe en memoria por ocho iconos en pantalla. Los que aún no
han llegado muestran su número mientras tanto.

Al cambiar, la emisión anterior se corta de inmediato: no se solapan ni la imagen ni el
sonido, y la conexión con el proveedor se libera antes de pedir la siguiente.

### Desplazarse por la reproducción

En películas y series la barra de progreso permite ir a cualquier punto:

- **Con el mando**: cuando la barra tiene el foco, izquierda y derecha la mueven. El paso
  parte de 10 segundos y aumenta mientras se mantiene pulsada la tecla, hasta un máximo de
  2 minutos.
- **Táctil**: una pulsación sobre un punto salta a él; el arrastre recorre la reproducción.

Mientras la barra se mueve se muestra el punto de destino y su diferencia respecto a la
posición actual. El salto se aplica al soltar, al pulsar OK o tras un instante sin
movimiento, de modo que recorrer una película no provoca una recarga por cada pulsación.

Las opciones **Retroceder 10 s** y **Avanzar 10 s** del menú permiten ajustes finos sin
necesidad de enfocar la barra.

### Gestos táctiles

En móvil y tablet, sobre la imagen:

| Gesto | Efecto |
| --- | --- |
| Tocar | Muestra el rótulo y la programación; con algo visible, lo oculta |
| Mantener pulsado | Abre el menú de opciones |
| Deslizar a izquierda o derecha | Cierra el vídeo y vuelve a la lista |
| Deslizar hacia arriba | Pantalla completa en horizontal, sin barras del sistema |
| Deslizar hacia abajo | Reduce a ventana flotante |

En pantalla completa se ocultan tanto la barra de estado como la de navegación; se recuperan
momentáneamente deslizando desde el borde. El menú incluye **Pantalla completa** y **Salir de
pantalla completa** para quien prefiera un botón al gesto; no aparecen en televisores, donde
no hay más que una orientación posible.

La orientación horizontal se impone durante unos segundos y después el acelerómetro recupera
el mando, de modo que girar el aparato vuelve a surtir efecto. Si el sistema tiene el giro
automático desactivado, la orientación se mantiene: soltarla devolvería la pantalla a su
posición bloqueada y el gesto no serviría de nada.

Al salir de la reproducción, orientación y barras vuelven a su estado anterior.

### El menú

Sobre la imagen sólo permanecen el rótulo de lo que se ve y la programación en curso. El resto
de opciones se abre manteniendo pulsado OK, o el dedo sobre el vídeo, y aparece como una lista
con los nombres completos:

- **Añadir o quitar de favoritos** — en canales y películas.
- **Ficha** — descripción completa del programa.
- **Guía** — programación del canal sin abandonar la reproducción.
- **Imagen** — alterna entre ajustar, zoom y estirar, para material que no se emite en 16:9.
  La opción permanece abierta, de modo que pueden probarse los tres modos seguidos.
- **Audio y subtítulos** — pistas disponibles en la emisión, con idioma y códec.
- **Enviar a…** — reproducción en otro aparato de la red.
- **Temporizador de apagado** — con los valores habituales, para no salir de la emisión a
  buscarlo. Muestra el tiempo restante cuando hay una cuenta en marcha y permite cancelarla.
  Es el mismo temporizador de [Ajustes](ajustes.md#temporizador-de-apagado), donde además
  puede fijarse un número de minutos a medida.
- **Ventana pequeña** — reduce la imagen sin cortar la emisión.

En películas y series se añaden la pausa y los saltos de 10 segundos.

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
