# Películas y series

## Los catálogos

![Películas](img/peliculas.png)

Las categorías del proveedor a la izquierda, la rejilla de carátulas a la derecha. El botón de
arriba cambia el **orden**: el del proveedor, por nombre, por añadido recientemente o por
valoración.

Las series funcionan igual, en su propia pestaña.

## Fichas

Al abrir una película se ve la carátula grande, la sinopsis, el año, la duración, la
valoración, la dirección y el reparto —lo que el proveedor haya enviado— y los botones para
reproducir o marcar como favorita.

En una serie, además, un selector de **temporada** y la lista de episodios de esa temporada.

Los datos vienen del panel tal cual, y los paneles son irregulares: si falta la sinopsis o la
carátula, es que no la mandan. Kanal lee los campos de forma tolerante, aceptando números
como texto o colecciones vacías servidas como `{}` en vez de `[]`, porque en la práctica cada
panel lo hace a su manera.

## Continuar viendo

Kanal guarda **dónde lo dejaste** en cada película y episodio. Al volver a abrirlo, el botón
principal dice «Continuar desde 42 min» y hay opción de empezar de cero.

Lo pendiente aparece en la portada, en **Continuar viendo**, con una barra de lo visto. Lo
terminado desaparece de esa fila.

El historial se guarda en el aparato, no en el proveedor, y se borra desde
[Ajustes](ajustes.md#borrar-historial).

## Favoritos

La estrella marca canales, películas y series por igual, y todo lo marcado se reúne en la
pestaña **Favoritos**. En la lista de canales, además, **Favoritos** es una categoría más de
la tira de arriba.

## Buscar

El buscador va sobre los canales, las películas y las series de la fuente activa, a partir de
dos letras. Es **insensible a acentos y mayúsculas**: «cancion» encuentra «Canción».

Para ordenar los canales alfabéticamente se ignora la numeración de delante, así que
`101. La 1` y `|ES| La 1` acaban donde uno espera y no todos juntos en los números.
