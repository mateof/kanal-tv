# Ajustes

Referencia de todos los ajustes, en el orden en que aparecen en la aplicación.

## Idioma

![Idioma](img/ajustes-idioma.png)

Kanal está disponible en **galego, castellano e inglés**.

- **Automático** (valor por defecto) adopta el idioma del aparato.
- Si ese idioma no está traducido, la interfaz se muestra en galego.
- El cambio se aplica de inmediato y se conserva entre sesiones.

Los formatos de fecha siguen el idioma seleccionado en la aplicación, no el del sistema.

## Fuentes

Alta, edición y eliminación de fuentes, y selección de la fuente activa mediante **Usar**. La
eliminación requiere confirmación y borra también el contenido cacheado de esa fuente.

Los campos de cada tipo de fuente se describen en
[Primeros pasos](primeros-pasos.md#alta-de-la-fuente).

## Reproducción

### Formato de emisión

`MPEG-TS (.ts)` o `HLS (.m3u8)`. MPEG-TS ofrece mejor compatibilidad con la mayoría de
aparatos; HLS tolera mejor las conexiones lentas.

Se trata de una preferencia: si un canal no responde en el formato indicado, Kanal prueba el
alternativo automáticamente.

### Búfer

| Perfil | Arranque | Búfer mínimo | Búfer máximo |
| --- | --- | --- | --- |
| Bajo | 0,8 s | 1,5 s | 15 s |
| Normal | 1,5 s | 5 s | 30 s |
| Alto | 3 s | 15 s | 60 s |
| Máximo | 5 s | 45 s | 180 s |

El valor de arranque determina cuánta emisión debe acumularse antes de mostrar imagen: los
perfiles altos tardan más en empezar y absorben mejor las variaciones de caudal.

En emisiones en directo, el búfer acumulado depende de lo que el servidor entregue por
delante del punto de emisión. Un proveedor que sirva en tiempo real estricto no permite
acumular los valores máximos, por lo que la diferencia entre perfiles altos resulta menos
perceptible que en contenido bajo demanda.

### Vista previa en la lista de canales

Reproduce el canal enfocado en el panel derecho tras un breve intervalo. Conviene
desactivarla en proveedores con un límite bajo de conexiones simultáneas.

### Aguantar cortes del servidor

Activado por defecto. Ante una interrupción de la emisión:

- reconecta automáticamente hasta seis veces, con espera creciente, indicando
  «Reconectando…» sin abandonar el canal;
- amplía a doce los reintentos por fragmento ante fallos de red, frente a los tres
  habituales.

La reconexión se aplica únicamente a errores de conexión. Los errores de contenedor o de
decodificación indican que los datos llegan dañados desde el origen, y reiniciar la emisión
en cada uno sustituiría un artefacto puntual por una interrupción completa.

### Recordar el último canal

Mantiene la emisión en la vista previa al salir de un canal con ATRÁS. Descrito en
[Televisión en directo](television.md#recordar-el-último-canal).

### User-Agent por defecto

Algunos proveedores sólo responden a agentes concretos. El valor predeterminado corresponde a
VLC. Puede definirse un agente distinto por fuente durante el alta.

## Guía y contenido

- **Días de guía a descargar** — 1, 2, 3, 5 o 7. Un intervalo mayor prolonga la
  sincronización y aumenta el espacio ocupado.
- **Sincronización automática** — periodicidad con la que se refresca el catálogo al abrir la
  aplicación.
- **Ocultar contenido para adultos** — excluye las categorías marcadas como XXX o +18.

## Apagado y ahorro

![Apagado y ahorro](img/ajustes-apagado.png)

### Temporizador de apagado

Permite programar el cierre de la aplicación transcurrido un intervalo, mediante valores
predefinidos entre 15 y 120 minutos o un valor personalizado entre 1 y 720.

Al activarlo se muestra la cuenta atrás. Durante el último minuto aparece un aviso con la
opción **Seguir viendo**, que cancela el temporizador. Al finalizar, la aplicación se cierra.

El temporizador no se conserva entre sesiones.

### Preguntar si sigues ahí

Activado por defecto. Transcurrida una hora sin interacción, la aplicación solicita
confirmación y se cierra si no obtiene respuesta en un minuto. Reduce el consumo de datos y
de energía cuando el aparato queda desatendido.

<img src="img/movil-sigues-ahi.png" width="300" alt="Aviso de inactividad">

Cualquier pulsación del mando se interpreta como respuesta afirmativa.

## Aplicación

- **Buscar actualizaciones automáticamente** — consulta las releases del repositorio al
  abrir la aplicación, con un intervalo mínimo de seis horas.
- **Registro detallado de red** — añade una entrada por cada petición HTTP. Recomendable sólo
  durante la investigación de un fallo.
- **Versión instalada**, con acceso a **Buscar actualizaciones**, **Descargar e instalar** y
  **Ver registro**.

### Borrar historial

Vacía la lista de reproducciones pendientes y el contenido visto recientemente. No afecta a
los favoritos.

### Vaciar caché de contenido

Elimina el catálogo y la guía descargados. Requiere sincronizar de nuevo. Resulta útil tras
una sincronización interrumpida.
