# Creación visual desde Chispa

## Implementado localmente

- Atajos «Crear imagen» y «Crear carrusel» que preparan una petición editable, sin generar ni cobrar al tocarlos.
- Recibos de generación guardados en los mensajes del asistente, procedentes de `generar_contenido` y `estado_generacion`, no de afirmaciones del modelo.
- Galería por proporción, con imágenes resueltas desde la biblioteca del mismo workspace. La selección de un carrusel mantiene su orden y no mezcla versiones.
- «Usar imagen», «Usar esta versión como carrusel» y «Crear variante» preparan el siguiente mensaje con los IDs reales adjuntos. No publican automáticamente ni sobrescriben el borrador existente.
- Vista previa de la selección junto al cuadro de texto. Los adjuntos se vuelven a validar en el servidor antes de usarse.
- Si una versión no pudo verificarse completa, se muestran las imágenes recuperadas sin ofrecer publicar el conjunto como carrusel completo.
- «Consultar resultado» retoma el mismo trabajo mediante el asistente. No es un botón de regeneración ni un sondeo automático en segundo plano.
- Los errores MCP se registran como llamadas fallidas y no producen recibos de acción completada.

El recorrido usa las herramientas y confirmaciones existentes. La petición de publicar debe desembocar en texto, redes y fecha verificados, más la confirmación correspondiente. Crear un borrador o programar no equivale a una entrega confirmada en una red social.

## Límites que deben comunicarse

- Una variante es otra generación con la imagen como referencia. No hay edición precisa de regiones, capas ni garantía de conservar idéntico el resto de la imagen.
- El carrusel del generador actual requiere de 2 a 5 fotos de referencia. El asistente debe pedirlas si faltan; no inventar IDs ni prometer un carrusel libre de esas restricciones.
- `ContenidoJobs` sigue en memoria. Los trabajos terminados se retienen 30 minutos; reiniciar la API pierde el seguimiento del trabajo. Las imágenes guardadas en Contenido no se eliminan por ello.
- El chat conserva los recibos que haya llegado a consultar y guardar, incluso si falla después la redacción del modelo. Esto no convierte el motor en una cola durable ni protege una transacción ante la caída del proceso antes de su commit.
- Los enlaces históricos no garantizan disponibilidad si se elimina el archivo. Usarlo nuevamente obliga a validar su existencia y workspace.
- No se han incorporado ejecutores independientes de Musa, Verso o Kiro ni delegaciones ficticias.

## Validación antes de producción

1. Con una marca lista y permiso de crear publicaciones, solicitar una imagen, comprobar el aviso de créditos y cancelar: no debe iniciarse la generación.
2. Confirmar una generación real, consultar su resultado y ampliar la imagen.
3. Usarla en publicación: comprobar que el archivo y el texto propuestos coincidan, elegir redes conectadas y fecha, revisar la confirmación. No publicar una prueba sin autorización.
4. Crear un carrusel con referencias, comprobar el orden por versión y que no se mezclen proporciones.
5. Pedir una variante describiendo un cambio; verificar una nueva confirmación de coste y que la pieza original permanezca.
6. Recargar el chat después de guardar el resultado; verificar galería y selección. Probar un trabajo expirado sin regeneración automática.
7. Probar falta de permisos, archivo eliminado, IDs de otro workspace, versión parcial y error del proveedor.

Pendiente: cola durable de generación y prueba E2E con servicios/modelo reales. Las pruebas locales usan mocks y no consumen créditos ni publican contenido.
