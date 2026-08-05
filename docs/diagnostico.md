# Diagnóstico

## Registro

Kanal escribe en un fichero lo que hace: sincronizaciones, peticiones, reproducciones y
errores. Se consulta en **Ajustes → Ver registro**, con filtro por nivel (todo, información,
avisos, errores).

Acciones disponibles:

- **Compartir** — abre el selector del sistema. Disponible en móvil y tablet.
- **Exportar a fichero** — guarda el registro en el almacenamiento y muestra la ruta.
- **Borrar**.

Las URL se registran redactadas: no incluyen credenciales del panel.

La opción **Registro detallado de red** añade una entrada por cada petición HTTP. Conviene
activarla sólo mientras se investiga un fallo concreto.

## Actualizaciones

Kanal consulta las releases del repositorio y avisa en la portada cuando hay una versión
nueva. **Actualizar** descarga el APK e inicia la instalación.

La primera vez, Android solicita permiso para instalar aplicaciones desde Kanal. Si se
deniega, el aviso lo indica y la operación puede repetirse.

La comprobación automática se realiza como máximo una vez cada seis horas.

## Problemas frecuentes

### La emisión no arranca

Kanal prueba otros contenedores para el mismo canal antes de mostrar un error. Si aun así
falla, las causas habituales son:

| Síntoma | Causa probable |
| --- | --- |
| «El servidor no envía vídeo en ningún formato reconocible» | Canal caído, o límite de conexiones del proveedor alcanzado |
| «El servidor rechazó la emisión» | Demasiadas conexiones simultáneas abiertas |
| «La emisión ya no existe en el servidor» | El canal ha desaparecido del panel; conviene sincronizar |
| «El dispositivo no puede decodificar esta emisión» | Códec no soportado por el aparato |

Probar otro canal permite distinguir entre un problema del canal y uno de la cuenta.

### La imagen se corta

Conviene identificar el origen antes de modificar ajustes:

- **Cortes con reconexión o errores de red**: activar
  [«Aguantar cortes del servidor»](ajustes.md#aguantar-cortes-del-servidor).
- **Imagen a tirones sin errores**: aumentar el
  [búfer](ajustes.md#búfer). Un búfer mayor retrasa el arranque pero absorbe mejor las
  variaciones de caudal.
- **Artefactos, bloques o congelaciones puntuales**: la señal llega dañada desde el origen.
  Ninguna opción del reproductor reconstruye datos que llegan corruptos; el problema está en
  la instalación o en el proveedor.

### El envío a otro aparato falla

| Síntoma | Qué comprobar |
| --- | --- |
| El televisor no aparece en la lista | Que admita DLNA y lo tenga habilitado. En LG suele estar en los ajustes de compartir pantalla o dispositivos conectados; en Samsung, dentro de las opciones de red. Debe estar encendido y en la misma red |
| Aparece pero rechaza el envío | El registro indica el código UPnP concreto. Kanal ya reintenta sin metadatos, que es la causa más frecuente |
| Reproduce pero se corta | El televisor descarga la emisión por su cuenta: le aplican los límites de conexiones del proveedor |

Si el aparato no aparece, puede añadirse indicando su dirección IP.

### La aplicación no se instala

Por orden de frecuencia:

1. **Falta de espacio** en el aparato. Es la causa más común y el mensaje del sistema no
   suele indicarlo.
2. **Orígenes desconocidos** no permitidos para la aplicación desde la que se instala.
3. **Versión anterior firmada con otra clave**. Es necesario desinstalarla primero.

Para obtener el motivo exacto:

```bash
adb install -r kanal-x.y.z.apk
```

El comando devuelve el código `INSTALL_FAILED_...` correspondiente.

### Un ajuste no parece tener efecto

El búfer y el agente de usuario se aplican al crear el reproductor. La vista previa de la
lista de canales se reconstruye al cambiar estos ajustes, pero una reproducción ya iniciada
mantiene los valores con los que empezó. Al abrir el canal de nuevo se aplican los nuevos.

## Recoger información para un informe de error

1. Activar **Registro detallado de red** en Ajustes.
2. Reproducir el fallo.
3. **Ver registro → Exportar a fichero**.
4. Desactivar de nuevo el registro detallado.

Conviene adjuntar también la versión instalada, que aparece en Ajustes, y el modelo del
aparato.
