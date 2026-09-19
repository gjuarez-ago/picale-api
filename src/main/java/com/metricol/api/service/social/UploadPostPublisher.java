package com.metricol.api.service.social;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import com.metricol.api.service.limits.LimitesConfigurables;
import com.metricol.api.service.publishing.ProviderCircuitBreaker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.metricol.api.config.UploadPostProperties;
import com.metricol.api.enums.Platform;
import com.metricol.api.service.ai.EspecTexto;
import com.metricol.api.service.media.AdaptadorDeImagenes;
import com.metricol.api.service.publishing.ConfirmacionDelProveedor;
import com.metricol.api.service.publishing.PostPublishStore;
import com.metricol.api.service.publishing.PublishOutcome;
import com.metricol.api.service.publishing.PublishPlan;

/**
 * Publica una publicación de verdad, en tres tramos: preparar, llamar,
 * apuntar.
 *
 * <p><b>Esta clase no tiene transacción, y es lo importante de su diseño.</b>
 * Las dos escrituras están en {@link PostPublishStore}, cada una en su propia
 * transacción corta, y la llamada al proveedor —que puede tardar medio
 * minuto— pasa por aquí sin ninguna abierta. Antes el publicado entero corría
 * dentro de una transacción; con un worker era invisible, pero con ocho
 * publicando a la vez son ocho conexiones de la base esperando a la red, y el
 * pool de produccion tiene cinco. El fallo no se habria visto como lentitud
 * del proveedor sino como una aplicacion entera congelada.
 */
@Service
public class UploadPostPublisher {

    private static final Logger log = LoggerFactory.getLogger(UploadPostPublisher.class);

    private final UploadPostClient client;
    private final UploadPostProperties props;
    private final PostPublishStore store;
    private final AdaptadorDeImagenes adaptador;
    private final ProviderCircuitBreaker breaker;
    private final LimitesConfigurables limites;

    /**
     * Quien le pregunta al proveedor como acabo de verdad cada envio. Es la
     * misma cadena que usa la reconciliacion manual, a proposito: las dos
     * tienen que llegar a la misma conclusion con los mismos datos.
     */
    private final ConsultaDeEnvio consulta;

    public UploadPostPublisher(
            UploadPostClient client,
            UploadPostProperties props,
            PostPublishStore store,
            AdaptadorDeImagenes adaptador,
            ProviderCircuitBreaker breaker,
            LimitesConfigurables limites,
            ConsultaDeEnvio consulta) {
        this.client = client;
        this.props = props;
        this.store = store;
        this.adaptador = adaptador;
        this.breaker = breaker;
        this.limites = limites;
        this.consulta = consulta;
    }

    /**
     * @param quedanIntentos si la cola volverá a llamar tras un fallo. Lo sabe
     *                       el runner, no esta clase, y hace falta aquí para
     *                       no marcar como fallida en la pantalla una
     *                       publicación que va a reintentarse en un minuto.
     */
    public PublishOutcome publish(UUID postId, UUID workspaceId, boolean quedanIntentos) {
        if (!props.isConfigured()) {
            String motivo = "Falta configurar la llave de upload-post.com en el servidor.";
            store.marcarFallidaDefinitiva(postId, motivo);
            return PublishOutcome.permanent(motivo);
        }

        // ¿Hay un envío ya entregado del que falte saber cómo acabó? Entonces
        // no se sube nada: se pregunta. Volver a subir aquí es lo que sacaría
        // el mismo video dos veces, porque el proveedor ya lo tiene.
        PostPublishStore.EnvioEnCurso enCurso = store.envioEnCurso(postId);
        if (enCurso != null) {
            return store.confirmar(postId, preguntar(enCurso));
        }

        PublishPlan plan = store.preparar(postId, workspaceId);
        if (plan.atajo() != null) {
            return plan.atajo();
        }
        if (!plan.hayQueEnviar()) {
            return PublishOutcome.permanent("No quedo ninguna red a la que publicar.");
        }

        // Se calcula aquí y no dentro de `enviar` porque hace falta dos veces:
        // para mandarlo y para guardarlo. Lo que se guarda tiene que ser
        // exactamente lo que salió — recalcularlo después daría el recorte de
        // ese momento, con los topes de ese momento.
        Map<String, String> porRed = textosPorRed(plan);

        Map<String, Object> respuesta;
        LocalDateTime entregadoEn = LocalDateTime.now(ZoneOffset.UTC);
        try {
            respuesta = enviar(plan, porRed);
        } catch (Exception ex) {
            // El envoltorio solo dice de donde viene; lo que hay que clasificar
            // y contarle a la persona es el rechazo de dentro.
            boolean sinAdaptar = ex instanceof MediosSinAdaptar;
            Exception real = sinAdaptar && ex.getCause() instanceof Exception causa ? causa : ex;

            if (real instanceof HttpClientErrorException limitado
                    && limitado.getStatusCode().value() == 429) {
                // Un 429 no es un fallo de ESTA publicacion: es de la llave, que es
                // una para todos. Se pausa la cola entera —ver ProviderCircuitBreaker—
                // y esta se aplaza sin gastar intento, para que ocho workers no
                // conviertan un "espera un poco" en un bloqueo.
                Duration pausa = retryAfter(limitado)
                        .orElse(Duration.ofSeconds(limites.pausaProveedorSegundos()));
                LocalDateTime hasta = breaker.abrir(pausa, "429 de upload-post publicando " + postId);
                String motivo = "El proveedor pidio bajar el ritmo. Se reintenta en unos minutos.";
                store.aplicarError(plan, motivo, true, porRed);
                return PublishOutcome.deferred(motivo, hasta.plusSeconds(5));
            }

            boolean recuperable = sinAdaptar || esRecuperable(real);
            String motivo = mensajeDe(real, recuperable);
            log.warn("Fallo publicando la publicacion {} ({}): {}",
                    postId, recuperable ? "se reintenta" : "definitivo", motivo);
            if (sinAdaptar) {
                log.warn("La publicacion {} salio con fotos sin adaptar y la rechazaron;"
                        + " se reintenta por si la proxima pasada si las encaja.", postId);
            }
            // El mensaje de arriba es el que lee la persona, y a proposito no
            // dice mas que el codigo. Para saber QUE le disgusto a upload-post
            // hace falta su respuesta, que solo sirve para diagnosticar.
            if (real instanceof HttpClientErrorException clientError) {
                log.warn("Respuesta de upload-post para {}: {}",
                        postId, clientError.getResponseBodyAsString());
            }
            store.aplicarError(plan, motivo, recuperable && quedanIntentos, porRed);
            return recuperable ? PublishOutcome.retryable(motivo) : PublishOutcome.permanent(motivo);
        }

        // Lo que contesta el proveedor a la subida es un acuse, no un
        // resultado: dice que acepta el encargo. Se guarda ANTES de preguntar
        // nada, porque a partir de aquí el video ya está en sus manos y un
        // reintento no debe volver a subirlo.
        String envio = identificadorDelEnvio(respuesta);
        store.guardarEnvio(postId, envio, entregadoEn);
        log.info("upload-post acepto la publicacion {} (envio {}). Respuesta: {}",
                postId, envio, resumen(respuesta));

        return store.aplicar(plan,
                preguntar(new PostPublishStore.EnvioEnCurso(envio, entregadoEn, plan.profile())),
                porRed);
    }

    /** La cadena de preguntas al proveedor vive en {@link ConsultaDeEnvio}; ver ahí el orden y el porqué. */
    private ConfirmacionDelProveedor preguntar(PostPublishStore.EnvioEnCurso envio) {
        return consulta.preguntar(envio);
    }

    /**
     * El identificador del envío dentro del acuse.
     *
     * <p>Se prueban varios nombres porque el proveedor no publica el esquema
     * de esta respuesta —{@code /openapi.json} y {@code /swagger.json} dan
     * 404— y los suyos propios usan los tres: en el historial y en el estado
     * conviven {@code request_id} y {@code job_id}. Si ninguno aparece, el
     * registro de arriba deja la respuesta entera escrita para verlo.
     */
    private String identificadorDelEnvio(Map<String, Object> respuesta) {
        if (respuesta == null) {
            return null;
        }
        for (String clave : List.of("request_id", "requestId", "job_id", "jobId")) {
            Object valor = respuesta.get(clave);
            if (valor != null && !valor.toString().isBlank()) {
                return valor.toString();
            }
        }
        return null;
    }

    /** La respuesta en el log, sin llenarlo si el proveedor se explaya. */
    private String resumen(Map<String, Object> respuesta) {
        String texto = String.valueOf(respuesta);
        return texto.length() <= 600 ? texto : texto.substring(0, 597) + "...";
    }

    /**
     * El texto de cada red, ya recortado a lo que esa red admite.
     *
     * <p>Se hace para TODAS y no solo para las que traen texto propio, y esa es
     * la mitad que salva el texto: el {@code title} común va recortado al
     * mínimo de las elegidas —o no pasa la validación—, así que sin esto
     * LinkedIn se quedaría con los 90 caracteres de TikTok. Dándole a cada una
     * el suyo, cada cual recibe lo que le cabe y nadie pierde texto por culpa
     * de la vecina.
     *
     * <p>Y se hace aquí, además de en el Redactor, porque por aquí pasa todo:
     * el texto de la IA, el que se escribió a mano y el que alguien editó
     * después. Un texto de más no se rechaza a medias —la red tira la
     * publicación entera—, y recortar el final es siempre mejor que no
     * publicar.
     *
     * <p>La llave es el nombre del enum ({@code FACEBOOK}), que es como lo
     * espera {@code UploadPostClient} y como se vuelve a buscar al guardarlo.
     */
    private Map<String, String> textosPorRed(PublishPlan plan) {
        Map<String, String> porRed = new LinkedHashMap<>();
        for (PublishPlan.Destino destino : plan.destinos()) {
            String suyo = destino.caption() == null || destino.caption().isBlank()
                    ? plan.caption()
                    : destino.caption();
            String cabe = EspecTexto.de(destino.platform()).recortar(suyo);
            if (cabe != null && !cabe.isBlank()) {
                porRed.put(destino.platform().name(), cabe);
            }
        }
        return porRed;
    }

    /**
     * Elige el endpoint según lo que se publica. Las fotos van todas en la
     * misma llamada: un carrusel es UNA publicación con varias imágenes, y
     * mandarlas de a una crearia varias publicaciones sueltas en la red.
     */
    private Map<String, Object> enviar(PublishPlan plan, Map<String, String> porRed) {
        List<String> platforms = plan.destinos().stream()
                .map(destino -> destino.platform().name().toLowerCase())
                .toList();

        // El `title` comun, recortado a lo que admite la mas estrecha de las
        // redes elegidas.
        //
        // Va UNA vez por envio y el proveedor lo valida contra todas, asi que
        // no basta con el recorte por red de arriba: hasta ahora el comun se
        // mandaba en crudo y era el que rompia. Un texto de 359 caracteres a
        // Facebook —que admite 255 en este campo— tiro la publicacion entera
        // con un 400, tres veces, y el recorte por red no llego a servir de
        // nada porque nunca era el que fallaba.
        //
        // Nadie pierde texto por esto: cada red recibe ademas el suyo en
        // `facebook_title` y companía, con todo lo que le quepa.
        //
        // Y sale del texto POR RED cuando lo hay, no del caption crudo de la
        // publicacion. El caption crudo es lo que la persona dicto o escribio;
        // el texto por red es lo que la IA redacto y ella aprobo en la vista
        // previa. Como el proveedor enseña el comun en varias redes aunque
        // reciba el propio, mandar el crudo aqui publicaba lo dictado.
        List<Platform> redesDelEnvio = plan.destinos().stream().map(PublishPlan.Destino::platform).toList();
        EspecTexto masEstricta = EspecTexto.masEstricta(redesDelEnvio);
        String baseComun = porRed.values().stream()
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .orElse(plan.caption());
        String tituloComun = masEstricta.recortar(baseComun);

        List<String> medios = plan.mediaUrls();
        if (medios.isEmpty()) {
            return client.publishText(plan.profile(), platforms, tituloComun);
        }
        if (plan.video()) {
            return client.publishVideo(plan.profile(), platforms, tituloComun, porRed,
                    medios.get(0), plan.formato());
        }

        // Aqui, y no antes, es donde las fotos se ajustan a lo que aceptan las
        // redes de ESTE envio. Dos motivos para que sea justo en este punto:
        //
        // - Ya se sabe a que redes se va. Al subir la foto no se sabia, y lo
        //   que hay que hacerle depende de eso: una vertical 9:16 se queda
        //   intacta si solo va a TikTok y hay que encajarla si va a Instagram.
        // - No hay ninguna transaccion abierta. Es la misma razon por la que
        //   la llamada HTTP se hace desde aqui (ver el comentario de la
        //   clase): convertir tarda, y hacerlo con una conexion de la base en
        //   la mano es lo que congela la aplicacion entera bajo carga.
        //
        // Si algo falla dentro, devuelve las mismas URLs y se publica con las
        // originales.
        AdaptadorDeImagenes.Adaptacion ajuste = adaptador.adaptar(
                plan.workspaceId(),
                medios,
                plan.destinos().stream().map(PublishPlan.Destino::platform).toList(),
                plan.formato());

        try {
            return client.publishPhotos(
                    plan.profile(), platforms, tituloComun, porRed, ajuste.urls(), plan.formato());
        } catch (RuntimeException ex) {
            if (!ajuste.huboFallo()) {
                throw ex;
            }
            // Se publico con alguna foto sin adaptar, asi que el rechazo puede
            // venir de ahi y no de la publicacion. Se marca para que cuente
            // como recuperable: ver MediosSinAdaptar.
            throw new MediosSinAdaptar(ex);
        }
    }

    /**
     * ¿Vale la pena volver a intentarlo?
     *
     * <p>Un corte de red, un 5xx o un 429 se arreglan esperando. Un 4xx que no
     * sea 429 es una peticion que la proxima vez sera igual de invalida: los
     * cuatro intentos darian el mismo error, y mientras tanto la persona
     * seguiria viendo "en cola" sin saber que ya no va a salir.
     */
    /**
     * Lo que el proveedor pide esperar, si lo dice. Solo se acepta la forma
     * en segundos; una fecha se ignora y se usa la pausa configurada.
     */
    private static Optional<Duration> retryAfter(HttpClientErrorException ex) {
        try {
            String valor = ex.getResponseHeaders() == null ? null : ex.getResponseHeaders().getFirst("Retry-After");
            if (valor == null || valor.isBlank()) {
                return Optional.empty();
            }
            long segundos = Long.parseLong(valor.strip());
            return segundos > 0 ? Optional.of(Duration.ofSeconds(segundos)) : Optional.empty();
        } catch (NumberFormatException ex2) {
            return Optional.empty();
        }
    }

    private boolean esRecuperable(Exception ex) {
        if (ex instanceof HttpClientErrorException clientError) {
            return clientError.getStatusCode().value() == 429;
        }
        if (ex instanceof HttpServerErrorException || ex instanceof ResourceAccessException) {
            return true;
        }
        // Lo desconocido se reintenta: un fallo transitorio mal clasificado
        // como definitivo pierde la publicacion, y al reves solo cuesta tiempo.
        return true;
    }

    /**
     * El proveedor rechazo un envio que llevaba fotos sin adaptar.
     *
     * <p>Existe para que ese rechazo no cuente como definitivo. Un 4xx
     * normalmente lo es —la proxima vez la peticion sera igual de invalida—,
     * pero si la conversion de las fotos fallo, la peticion NO sera igual: el
     * siguiente intento vuelve a adaptarlas, y lo que rompio la conversion
     * suele ser cosa de un momento (ffmpeg ocupado, un temporal a medias, un
     * corte al bajar el original). Dandolo por perdido al primer intento, una
     * publicacion se quedaba sin salir por algo que se arreglaba solo.
     *
     * <p>El coste de equivocarse es asimetrico, y por eso se elige asi: si el
     * rechazo era de verdad definitivo se gastan los intentos que queden y se
     * tarda unos minutos mas en decir lo mismo; al reves, se pierde la
     * publicacion.
     */
    private static class MediosSinAdaptar extends RuntimeException {
        MediosSinAdaptar(RuntimeException causa) {
            super(causa.getMessage(), causa);
        }
    }

    private String mensajeDe(Exception ex, boolean recuperable) {
        if (ex instanceof HttpClientErrorException clientError) {
            int status = clientError.getStatusCode().value();
            if (status == 429) {
                return "El proveedor pidio bajar el ritmo. Se reintenta en unos minutos.";
            }
            if (status == 401 || status == 403) {
                return "upload-post.com rechazo las credenciales. Revisa la conexion en Ajustes.";
            }
            return "upload-post.com rechazo la peticion (" + status + ").";
        }
        if (ex instanceof HttpServerErrorException) {
            return "upload-post.com tuvo un error interno. Se reintenta.";
        }
        if (ex instanceof ResourceAccessException) {
            return "No se pudo contactar a upload-post.com. Se reintenta.";
        }
        String detalle = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return (recuperable ? "No se pudo publicar: " : "No se pudo publicar (definitivo): ") + detalle;
    }
}
