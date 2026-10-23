# Mover a Chispa de `chat/completions` a `responses`

## Por qué, en una línea

Con `gpt-6` ya no se puede razonar y usar herramientas a la vez en
`/v1/chat/completions`. Chispa manda su catálogo en cada llamada, así que hoy
corre **sin razonar**.

Comprobado contra la API, no deducido:

```
luna + herramientas + razonamiento  → 400
luna + herramientas + 'none'        → OK
luna + herramientas, sin indicar    → 400  (por omisión ya razona)
sol  + herramientas + razonamiento  → 400
```

El propio error dice la salida: *«To use function tools, use /v1/responses or
set reasoning_effort to 'none'»*. Elegimos `none` como parche para que el chat
no quedara roto; esto es el arreglo.

En `/v1/responses` la misma petición funciona:

```
OK | modelo: gpt-6-luna
tipos de salida: reasoning, function_call
llamó a: panel con {"dias":1}
```

## Qué gana el producto

1. **Chispa razona antes de elegir herramienta.** Es la diferencia entre acertar
   con la herramienta a la primera y dar tres vueltas.
2. **La continuación puede referenciar el estado previo.** Con `previous_response_id`
   no es necesario reenviar todo el historial por HTTP. Esto no significa que solo
   se facturen o procesen los tokens nuevos: la documentación oficial indica que
   los tokens anteriores de la cadena siguen contando como entrada. La cifra
   histórica de 50 tokens no demuestra una reducción equivalente de consumo ni de
   límites de tasa. En la prueba local del 21 de octubre, una continuación reportó
   19.711 tokens de entrada, de los cuales 18.661 estaban en caché. Medir ambos datos.
   Fuente: [Conversation state](https://developers.openai.com/api/docs/guides/conversation-state).
3. **Es el motor de la vuelta agéntica diaria.** Ese bucle necesita exactamente
   esto: mirar, pensar y decidir qué herramienta usar. Migrar una vez sirve para
   las dos cosas; no migrar haría nacer al agente diario ya cojo.

## Alcance

**Un archivo**: `AssistantLlmClient`. El orquestador no se entera: sigue
recibiendo su `Turn` con texto y llamadas. Políticas, confirmaciones, los 12
pasos, los recibos y las tarjetas quedan intactos.

### Lo que cambia de forma

| | Hoy | Después |
|---|---|---|
| Entrada | `messages: [...]` | `input: [...]` |
| Herramienta | `{type, function:{name, parameters}}` | `{type:"function", name, parameters}` — plano |
| Razonamiento | `reasoning_effort: "high"` | `reasoning: {effort: "high"}` |
| Salida | `choices[0].message` | `output: [...]`, lista de items |
| Llamada | `message.tool_calls[]` | item `type:"function_call"` con `call_id` |
| Resultado | mensaje `role:"tool"` | item `type:"function_call_output"` con ese `call_id` |
| Tokens | `prompt_tokens` / `completion_tokens` | `input_tokens` / `output_tokens` |

### Los cuatro detalles que deciden si sale bien

1. **`call_id`, no `id`.** Un `function_call` trae los dos. El que se devuelve
   en `function_call_output` es `call_id`. Confundirlos da un error que no
   explica nada.
2. **El razonamiento hay que devolverlo.** El modelo emite un item
   `type:"reasoning"` antes de la llamada. Si no vuelve en la siguiente vuelta
   —por `previous_response_id` o reenviándolo— pierde el hilo entre una
   herramienta y la siguiente. **Una migración que lo tire empeora las cosas**:
   razona y luego olvida lo que pensó.
3. **El item de razonamiento no siempre aparece.** En una pregunta trivial
   `reasoning_tokens` salió 0 y no hubo item. Tratar su ausencia como error
   rompería los casos fáciles.
4. **Los tokens de razonamiento cuentan como salida**, y vienen desglosados en
   `output_tokens_details.reasoning_tokens`. `AiUsageRecorder` debe leer los
   nombres nuevos o **el costo se registrará en cero sin avisar** — el mismo
   fallo silencioso que ya tuvimos al cambiar de modelo sin tocar los precios.

### Qué NO entra

- El `OpenAiClient` general (organizador, redactor, perfilador, director). No
  mandan herramientas, así que ya razonan bien donde están. Se quedan.
- El bucle, las políticas y las tarjetas del orquestador.
- La vuelta agéntica diaria: es el trabajo siguiente, sobre este motor.

## Cómo se prueba

El cliente ya acepta una URL base por constructor (se añadió para la prueba del
límite de tasa), así que todo esto se prueba con el `HttpServer` del JDK, sin
red y sin gastar:

- Una llamada con herramientas lleva `reasoning.effort` y el esquema plano.
- Una respuesta con `function_call` produce un `Turn` con esa llamada y su
  `call_id`.
- Una respuesta con `message` y sin llamadas produce un `Turn` con el texto.
- Una respuesta sin item de razonamiento no falla.
- El resultado de una herramienta viaja como `function_call_output` con el
  `call_id` que vino, y el razonamiento anterior no se pierde.
- `input_tokens` / `output_tokens` llegan al registro de uso.
- Un 429 sigue esperando y reintentando (lo que ya existe no se rompe).

Además, los 17 casos de `AssistantOrchestratorTest` tienen que seguir en verde
sin tocarlos: son la prueba de que el orquestador no se enteró del cambio.

## Cómo volver atrás

El endpoint se elige por configuración (`app.assistant.api: responses|chat`),
con `chat` como salida de emergencia. Si algo sale mal en producción se vuelve
cambiando una variable y reiniciando, sin desplegar código.

Esa bandera se quita cuando lleve una semana sin usarse.

## Orden

1. Traducir petición y respuesta, con los seis casos de prueba.
2. Enganchar el uso de tokens con los nombres nuevos.
3. Encender `responses` en local y subir `assistant-reasoning-effort` a `high`.
4. Probar a mano el alta de marca y una acción con confirmación.
5. Producción con la bandera, y vigilar `ai_usage` el primer día.

## Lo que esto no resuelve

- **El agente autónomo sigue sin usar nada de esto.** Es el trabajo siguiente.
- **El límite de 30 000 TPM.** Baja el consumo, no sube el techo; pedir más
  sigue pendiente.
- **La redacción robótica.** Vive en `Redactor`, por otro camino.
