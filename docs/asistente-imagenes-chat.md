# El chat de imágenes: que se sienta un chat

Lo que hay que arreglar después de probarlo en el teléfono (8 oct 2026).

## Lo que ya funciona y no se toca

El asistente **propone de verdad**. En la prueba real dijo *"Yo haría una
publicación para el muro con una foto profesional tuya… ¿Vamos con eso?"* y
*"Propongo estas frases: 1) … 2) … ¿Cuál te late más?"*. Esa era la duda
grande —si proponía o interrogaba— y está resuelta. El prompt no se toca.

## Lo que falla

Seis cosas, dichas como se vieron:

1. **Ocupa mucho espacio.** La ficha es un bloque azul de cuatro líneas y los
   mensajes son burbujas anchas. Cabe poca conversación en pantalla.
2. **Lo que escribo tarda en aparecer.** Mi mensaje no se ve hasta que el
   servidor contesta, así que parece que no se mandó.
3. **No se ve que esté preparando algo.** Un "Pensando…" en gris pálido no
   transmite que hay alguien trabajando.
4. **Se vuelve rígido después de elegir.** Al elegir una imagen desaparece la
   posibilidad de cambiar la propuesta: solo queda publicar.
5. **No se guardan los chats.** No hay forma de volver a una conversación de
   ayer.
6. **Las imágenes no se quedan en la conversación.** Se ven mientras estás
   ahí; si sales, desaparecen.

Y un fallo que se ve en la captura: **al mandar solo fotos sale una burbuja
azul vacía**, porque el texto va en blanco.

## Las fases

### Fase 1 — Que se sienta vivo (solo app)

Lo que más se nota y lo más barato.

- **Eco optimista**: tu mensaje aparece **al instante**, antes de que el
  servidor conteste. Es la diferencia entre un chat y un formulario lento.
- **Puntitos animados** mientras prepara la respuesta, donde va a salir.
- **Arreglar la burbuja vacía** de cuando solo mandas fotos: debe decir que
  mandaste una foto, y enseñarla.
- **Que las opciones no se corten.** Hoy se salen de la pantalla
  ("Solo texto principa…"); tienen que envolver en dos filas.
- **No bloquear después de elegir**: seguir hablando, volver a crear y
  publicar, los tres a la vez.

### Fase 2 — Que quepa más conversación (solo app)

- **La ficha, a una línea** tocable que se despliega. Hoy son cuatro y empuja
  todo hacia abajo.
- **El asistente, sin burbuja pesada**: texto suelto alineado a la izquierda,
  como ChatGPT. La burbuja se queda solo para lo tuyo.
- **Quitar lo repetido**: "Solo me falta saber…" dice lo mismo que la ficha.

### Fase 3 — Que no se pierda lo creado (servidor + app)

Las imágenes creadas se guardan **en la conversación**, como un turno más. Al
volver siguen ahí, se pueden ver y elegir.

Hoy viven en memoria de la pantalla: sales y se pierden. Siguen en Contenido
—el generador las guarda ahí—, pero la conversación se queda coja.

### Fase 4 — Guardar los chats (servidor + app)

Lista de conversaciones con su título —sacado de lo que se anunciaba— y la
fecha. Se toca una y se abre donde se dejó.

### Fase 5 — Detalles que se notan al usar

- Que al llegar se baje solo al último mensaje.
- Que el teclado no tape lo que se escribe.
- Que se pueda copiar lo que dijo el asistente.

### Fase 5 — Segunda revisada: los huecos que quedaban

Leyendo el código con ojos de quien no sabe nada, aparecieron cinco cosas que
ninguna prueba iba a encontrar:

1. **El cuadro vacío al abrir.** El saludo dice "cuéntame qué quieres
   anunciar" y debajo no hay nada que tocar. **Es el punto donde se abandona**:
   quien no sabe qué se espera de él, cierra. Hacen falta ejemplos que se
   toquen: "Una promoción", "Un producto", "Que abrimos hoy".
2. **No hay flecha para salir.** Funciona el gesto de Android, pero quien no
   es técnico busca la flecha. Si no la ve, se siente atrapado.
3. **Se elige a ciegas.** Tocas una imagen y queda elegida, sin poder verla en
   grande antes. En un teléfono, 140 píxeles de ancho no bastan para decidir.
4. **No se ve cuál elegiste.** Después de tocar, las tres se siguen viendo
   igual.
5. **Si sales mientras crea, se pierde.** El sondeo vive en la pantalla: al
   salir, las imágenes nunca llegan a la conversación. Se pagaron y se
   quedaron solo en Contenido, sin que nadie lo diga.

Y los detalles de uso que ya estaban apuntados: bajar solo al último mensaje,
que el teclado no tape, poder copiar lo que dijo el asistente, y poder borrar
una conversación del historial.

## Cómo se mide que salió bien

La prueba de antes sigue valiendo: **cuántas veces hubo que escribir en vez de
tocar**. Y se le suma una nueva, mirando la pantalla: **cuántos mensajes de la
conversación caben a la vez**. Hoy caben tres. Deberían caber seis.
