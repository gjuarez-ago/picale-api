# Onboarding conversacional y resultados visuales

## Implementado en esta iteración

- Panel de avance en Asistente basado en `workspace/brand`, con porcentaje guardado y campos pendientes.
- Acción «Completar juntos» que retoma la conversación del espacio. No dispara solicitudes por abrir la página.
- Acciones por campo pendiente y acceso a Marca y Redes. Se respeta `WORKSPACE_EDIT`.
- Reconsulta de la marca tras terminar un turno o confirmar una acción. Si falla, se informa y se ofrece reintentar.
- Instrucciones de onboarding: consultar primero, conservar datos, proponer redacción y tono, pedir hechos desconocidos en grupos pequeños, guardar solo lo confirmado y verificar el resultado.
- Separación entre perfil básico al 100%, personalización y conexión de redes.
- Actividad del turno derivada de eventos reales de herramientas; no representa agentes delegados.
- Fotos ampliables, videos reproducibles aunque tengan miniatura, archivos accesibles y espaciado del texto generado.

## Límites actuales

El porcentaje mide los ocho apartados existentes en BrandService, no calidad de marca ni ventas. No se modifica su fórmula ni se rellenan campos con valores ficticios para subir el porcentaje.

Musa, Verso, Kiro, Pulso y Ritmo tienen recursos gráficos, pero esta iteración no incorpora ejecutores independientes ni ejecución concurrente. Las instrucciones impiden anunciar delegaciones inexistentes.

## Siguiente integración del equipo

Cada tarea delegada necesita un identificador durable, espacio y usuario solicitante, agente responsable, herramientas autorizadas, estado, resultado y error. Las confirmaciones y permisos existentes deben aplicarse también a cada ejecutor.

Chispa coordina el onboarding y mantiene la conversación. Verso puede encargarse de propuestas editoriales, Musa de dirección creativa, Kiro de producción audiovisual, Pulso de análisis y Ritmo de programación cuando existan esas capacidades registradas. Una operación en cola debe mostrarse como pendiente hasta que el servicio informe su resultado.

Las gráficas de rendimiento requieren series obtenidas de herramientas, con fuente, fechas y unidades; no números escritos libremente por el modelo. La barra de preparación implementada usa el porcentaje que devuelve la API.

## Comprobación con una cuenta real antes de liberar

1. Abrir un espacio incompleto y contrastar pendientes con Marca.
2. Tocar «Completar juntos», revisar una propuesta y rechazar o modificar un campo.
3. Confirmar el guardado y comprobar que el porcentaje coincide con el nuevo estado de Marca.
4. Salir y regresar: la conversación y los datos confirmados deben conservarse.
5. Probar falta de permisos, error al guardar y fallo al consultar el avance.
6. Completar los ocho apartados sin inventar contacto ni historia; verificar que se ofrece continuar con redes y contenido sin afirmar que ya están listos.

Las pruebas unitarias no sustituyen este recorrido contra el modelo y los servicios en ejecución.
