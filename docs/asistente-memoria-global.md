# Chispa como memoria operativa global

## Decisión de producto

Cada workspace tiene una sola conversación continua con Chispa. La persona no
administra hilos, títulos ni archivos de chat: entra y continúa donde quedó su
negocio. Cuando Picale incorpore más agentes, estos colaborarán dentro de la
misma continuidad y se identificarán por su especialidad, no como chats
aislados.

## Lo implementado

- La API devuelve y reutiliza únicamente la conversación más reciente del
  workspace; crear otra vez es idempotente y reabre la continuidad existente.
- Las conversaciones anteriores no se eliminan: dejan de exponerse en la UI y
  se conserva su información para una migración controlada.
- La web ya no muestra lista, botón de conversación nueva, renombrado, borrado
  ni cierre. Chispa ocupa la vista completa.
- Los resultados sanitizados del MCP alimentan una memoria operativa acotada:
  dato consultado, acción realizada o intento que no se completó, siempre con
  fecha.
- La memoria se entrega al modelo como contexto histórico. El prompt le exige
  consultar nuevamente el MCP antes de afirmar estados actuales o actuar.
- La memoria tiene un límite de 6 000 caracteres para evitar que el contexto y
  el costo crezcan sin control.

## Qué aprende y qué no

Chispa puede recordar evidencia útil: qué se consultó, qué acción sí terminó y
qué intento devolvió un error. No convierte automáticamente una frase de la
persona en un hecho permanente y no considera una respuesta antigua como estado
actual.

Esta separación evita tres fallas peligrosas:

1. Recordar como verdad una suposición del modelo.
2. Recomendar con métricas vencidas.
3. Repetir una acción porque el chat olvidó su recibo.

## Evolución profesional de la memoria

La siguiente etapa debe usar memorias tipadas, no solo vectorizar todo el chat:

| Tipo | Ejemplo | Fuente | Vigencia |
|---|---|---|---|
| Preferencia | “La marca evita promociones agresivas” | Persona, con edición visible | Hasta que cambie |
| Decisión | “Esta semana priorizamos alcance” | Confirmación de la persona | Periodo definido |
| Hecho operativo | “Se creó el borrador del viernes” | Recibo MCP | Histórico |
| Resultado | “Los reels superaron a las fotos” | Métricas MCP | Ventana y fecha explícitas |
| Restricción | “Facebook está desconectado” | MCP | Debe revalidarse |

Cada memoria futura debe guardar `workspace`, tipo, contenido, fuente, fecha,
vigencia, confianza y referencia a la evidencia. La persona debe poder ver,
corregir y olvidar preferencias. Datos actuales y permisos nunca se resuelven
desde vectores: siempre vienen del MCP.

## Ritmo diario y semanal

La conversación global debe ofrecer, sin crear pantallas nuevas:

- **Hoy:** pendientes, publicaciones próximas, fallos y una recomendación.
- **Esta semana:** objetivo, piezas necesarias, decisiones pendientes y carga de
  trabajo.
- **Cierre semanal:** qué salió, qué funcionó, qué aprender y qué ajustar.

Los accesos `arranque_del_dia`, `planear_semana` y `reporte_semanal` continúan
siendo prompts del catálogo MCP. Así el árbol operativo y sus límites se
mantienen en un solo lugar.

## Antes de vectorizar

1. Crear la tabla de memorias tipadas y su auditoría.
2. Definir retención, exportación y borrado por workspace.
3. Extraer preferencias y decisiones con revisión humana cuando sean ambiguas.
4. Evaluar recuperación con preguntas reales y medir falsos recuerdos.
5. Vectorizar solo conocimiento extenso y estable; no permisos, saldos, estados
   de publicaciones ni métricas vivas.

Vectorizar el historial completo sin esta capa mezclaría hechos, propuestas,
errores y datos vencidos. La memoria profesional debe ser explicable y
corregible.
