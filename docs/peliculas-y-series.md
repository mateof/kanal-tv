# Películas y series

## Catálogos

![Películas](img/peliculas.png)

Las categorías del proveedor ocupan la columna izquierda y las carátulas la rejilla derecha.
El botón superior alterna el criterio de ordenación: orden del proveedor, nombre, añadido
recientemente o valoración.

Las series disponen de una sección equivalente.

## Fichas

La ficha de una película muestra carátula, sinopsis, año, duración, valoración, dirección y
reparto, junto con los botones de reproducción y de favoritos.

En las series se añade un selector de temporada y el listado de episodios correspondiente.

Los metadatos proceden del panel. Su disponibilidad varía según el proveedor: los campos
ausentes no se muestran. Kanal admite las variaciones habituales de formato, como valores
numéricos enviados como texto o colecciones vacías representadas mediante `{}`.

## Reanudación

Kanal registra la posición de cada película y episodio. Al abrirlos de nuevo, el botón
principal ofrece continuar desde ese punto, con la alternativa de empezar desde el principio.

La posición se guarda al pausar, al salir de la reproducción y de forma periódica mientras se
reproduce, de modo que no se pierde si la aplicación se cierra de forma inesperada. El
contenido reproducido más allá del 95 % se considera terminado y deja de ofrecerse para
continuar.

El contenido pendiente aparece en la portada, bajo **Continuar viendo**, con indicación del
progreso. Los títulos completados dejan de mostrarse.

El historial se almacena en el aparato y puede borrarse desde
[Ajustes](ajustes.md#borrar-historial).

## Favoritos

Canales, películas y series admiten marcado como favoritos, y todos ellos se agrupan en la
sección **Favoritos**. En la lista de canales, además, **Favoritos** figura como una categoría
más de la franja superior.

## Búsqueda

La búsqueda abarca canales, películas y series de la fuente activa a partir de dos
caracteres, sin distinguir mayúsculas ni acentos.

En la ordenación alfabética de canales se ignora la numeración inicial, de modo que entradas
como `101. La 1` o `|ES| La 1` se ordenan por el nombre del canal.
