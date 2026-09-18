package com.metricol.api.service.social;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.metricol.api.config.UploadPostProperties;
import com.metricol.api.entity.SocialConnectionCheck;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.Platform;
import com.metricol.api.repository.SocialConnectionCheckRepository;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * Conectar las cuentas de redes sociales de un workspace, vía upload-post.com.
 * Portado de {@code UploadPostConnectService} de vivento366.api, que ya tenía
 * este flujo probado contra el proveedor real.
 *
 * <p>Aquí no se guarda ningún token de ninguna red: esos viven del lado de
 * upload-post. Lo único que hace este servicio es abrir la puerta para que la
 * persona conecte su cuenta, y después preguntar qué quedó conectado.
 *
 * <p>El "perfil" de upload-post se identifica con el id del workspace, igual
 * que vivento usa su {@code tenantId}: así cada workspace tiene el suyo y no
 * se cruzan. La diferencia es que aquí, además, ese perfil se persiste en
 * {@link Workspace#getUploadPostProfile()} — que es exactamente el campo que
 * {@link UploadPostPublisher} ya lee para publicar. Sin eso quedarían dos
 * nociones de perfil: la que se conecta y la que publica.
 *
 * <p>No se porta el {@code publishReel} de vivento: metricol ya publica con
 * {@link UploadPostClient} y {@link UploadPostPublisher}, y duplicar esa parte
 * dejaría dos caminos de publicación compitiendo.
 */
@Service
public class UploadPostConnectService {

    private static final Logger log = LoggerFactory.getLogger(UploadPostConnectService.class);

    private final UploadPostProperties props;
    private final SocialConnectionCheckRepository connectionCheckRepository;
    private final WorkspaceRepository workspaceRepository;
    private final SocialAccountSyncService accountSync;
    private final RestClient restClient;

    @Value("${app.base-url:http://localhost:5050}")
    private String appBaseUrl;

    /** Ver {@link #redirectUrl()}. Se cuelga de {@code app.base-url}. */
    @Value("${app.social-connect.redirect-path:/social-connected.html}")
    private String redirectPath;

    public UploadPostConnectService(
            UploadPostProperties props,
            SocialConnectionCheckRepository connectionCheckRepository,
            WorkspaceRepository workspaceRepository,
            SocialAccountSyncService accountSync) {

        this.props = props;
        this.connectionCheckRepository = connectionCheckRepository;
        this.workspaceRepository = workspaceRepository;
        this.accountSync = accountSync;

        // Las rutas de conexión viven bajo /uploadposts, un nivel más abajo
        // que las de publicación (/upload, /upload_photos) que ya usa
        // UploadPostClient. Se cuelgan de la misma base configurada para no
        // tener dos URLs del proveedor repartidas por el código.
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl() + "/uploadposts")
                .defaultHeader("Authorization", "Apikey " + props.getApiKey())
                .build();
    }

    /**
     * El enlace a la pantalla de conexión hospedada por upload-post, con la
     * marca del workspace. Es el camino corto: una sola URL que ofrece todas
     * las redes, sin que metricol tenga que construir pantalla propia.
     */
    public String connectLink(Workspace workspace) {
        return connectLink(workspace, List.of());
    }

    /**
     * El enlace a la pantalla de conexión de upload-post, opcionalmente
     * limitada a unas redes.
     *
     * <p>Con {@code platforms} vacío ofrece todas; con una sola, esa pantalla
     * hace de "conectar Facebook" sin que tengamos que armar nada.
     *
     * <p><b>Esta es la vía buena, y no el OAuth crudo de
     * {@link #oauthStartUrl}.</b> La diferencia se ve en Facebook: el OAuth
     * directo deja a la persona en el diálogo de Meta, donde las Páginas
     * vienen SIN marcar y es facilísimo darle a continuar sin conceder
     * ninguna. Eso deja la cuenta autorizada pero sin nada donde publicar —la
     * API contesta {@code "facebook": ""} y {@code /facebook/pages} vacío— y
     * desde fuera parece que la conexión simplemente no funcionó. La pantalla
     * de upload-post guía ese paso, y mantenerla es problema suyo, no
     * nuestro.
     */
    public String connectLink(Workspace workspace, List<String> platforms) {
        requireConfigured();

        String username = ensureProfile(workspace);

        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("connect_title", "Conecta tus redes, "
                + (workspace.getName() == null || workspace.getName().isBlank() ? "Picale" : workspace.getName()));
        body.put("connect_description",
                "Conecta tus cuentas para publicar desde tus propios perfiles.");
        body.put("language", "es");
        body.put("redirect_url", redirectUrl());
        // El texto del botón de vuelta: por defecto dice algo genérico y
        // deja a la persona sin saber que ya terminó.
        body.put("redirect_button_text", "Volver a Picale");

        if (!platforms.isEmpty()) {
            body.put("platforms", platforms);
        }

        Map<?, ?> respuesta = post("/users/generate-jwt", body,
                "No se pudo generar el enlace de conexión. Intenta de nuevo en un momento.");

        Object url = respuesta == null ? null : respuesta.get("access_url");
        if (url == null) {
            log.error("upload-post no devolvio access_url. Perfil={}, respuesta={}",
                    username, respuesta);
            throw new IllegalStateException("upload-post.com no devolvió un enlace de conexión.");
        }

        log.info("Enlace de conexion generado. Perfil={}, redirect_url={}, destino={}",
                username, redirectUrl(), soloHost(url.toString()));
        return url.toString();
    }

    /**
     * El enlace para conectar UNA red concreta, para cuando la pantalla la
     * pinta metricol en vez de mandar a la hospedada de
     * {@link #connectLink}. Devuelve solo la URL de autorización de esa red.
     *
     * @param platform {@code tiktok}, {@code instagram}, {@code facebook},
     *                 {@code linkedin} o {@code youtube}
     */
    public String oauthStartUrl(Workspace workspace, String platform) {
        requireConfigured();

        String username = ensureProfile(workspace);

        Map<String, Object> body = Map.of(
                "profile", username,
                "redirect_url", redirectUrl());

        Map<?, ?> respuesta = post("/oauth/" + platform + "/start", body,
                "La red rechazó iniciar la conexión.");

        Object url = respuesta == null ? null : respuesta.get("authorize_url");
        if (url == null) {
            log.error("upload-post no devolvio authorize_url para {}. Perfil={}, respuesta={}",
                    platform, username, respuesta);
            throw new IllegalStateException("upload-post.com no devolvió un enlace de autorización.");
        }

        log.info("Autorizacion iniciada en {}. Perfil={}, redirect_url={}, destino={}",
                platform, username, redirectUrl(), soloHost(url.toString()));
        return url.toString();
    }

    /**
     * Solo el host de una URL, para poder registrarla sin filtrar nada.
     *
     * <p>Las URLs que devuelve upload-post llevan un JWT de un solo uso en la
     * ruta o en la query. Escribirlas enteras en el log dejaría una
     * credencial en un archivo que se comparte al pedir ayuda — y el host ya
     * contesta la pregunta que importa: a dónde va a mandar a la persona.
     */
    private String soloHost(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException ex) {
            return "(url ilegible)";
        }
    }

    /**
     * Qué redes tiene conectadas este workspace, según upload-post, y de paso
     * refleja eso en las {@code social_accounts} de metricol para que la app
     * pueda elegirlas al publicar.
     *
     * <p>upload-post devuelve un perfil con {@code social_accounts}: una llave
     * por red, en {@code null} si no está conectada, o un objeto si sí. Si el
     * perfil todavía no existe (nadie ha conectado nada) contesta 404, y aquí
     * eso se traduce a "ninguna conectada" en vez de reventar.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> connectionStatus(Workspace workspace) {
        requireConfigured();

        String username = profileOf(workspace);

        Map<?, ?> cuerpo;
        try {
            cuerpo = restClient.get()
                    .uri("/users/" + username)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException.NotFound ex) {
            // 404 = el perfil no existe todavia en upload-post. No es un
            // error, pero si la respuesta a "conecte las redes y siguen sin
            // aparecer": se conectaron en un perfil distinto del que se esta
            // consultando.
            log.info("upload-post no conoce el perfil {}; se toma como ninguna red conectada", username);
            accountSync.sync(Map.of());
            return Map.of();
        } catch (HttpClientErrorException ex) {
            log.error("upload-post rechazó consultar el perfil de {}: {} {}",
                    username, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw errorDeUploadPost(ex, "No se pudo consultar el estado de las conexiones.");
        } catch (RestClientException ex) {
            log.error("No se pudo hablar con upload-post para consultar {}: {}", username, ex.getMessage());
            throw new IllegalStateException("No se pudo contactar a upload-post.com ahora mismo.");
        }

        Object profileObj = cuerpo == null ? null : cuerpo.get("profile");
        if (!(profileObj instanceof Map)) {
            return Map.of();
        }
        Map<String, Object> profile = (Map<String, Object>) profileObj;

        Object socialAccountsObj = profile.get("social_accounts");
        if (!(socialAccountsObj instanceof Map)) {
            return Map.of();
        }
        Map<String, Object> socialAccounts = new HashMap<>((Map<String, Object>) socialAccountsObj);

        // Antes de leer la página fijada, fijarla si solo hay una.
        //
        // Facebook y LinkedIn no publican en un perfil personal, solo en una
        // Página, y si no hay ninguna fijada la cuenta vuelve conectada pero
        // sin datos usables —lo que se veía en el registro como "quedó
        // autorizada pero sin datos usables"—. Quien conecta no tiene forma de
        // saber que le falta un paso: autorizó, la red sale en verde, y
        // publicar falla.
        //
        // Con una sola página no hay nada que preguntar: es esa. Con varias sí
        // se pregunta, porque elegir por la persona en cuál de sus negocios se
        // publica es peor que pedirle un toque.
        Map<String, Object> perfilConPagina = new HashMap<>(profile);
        fijarSiSoloHayUna(perfilConPagina, socialAccounts, username, "facebook",
                "facebook_page_id", "facebook_page_name", "/facebook/pages",
                "/users/facebook-page");
        fijarSiSoloHayUna(perfilConPagina, socialAccounts, username, "linkedin",
                "linkedin_page_id", "linkedin_page_name", "/linkedin/pages",
                "/users/linkedin-page");

        copiarPaginaFija(socialAccounts, perfilConPagina, username, "facebook",
                "facebook_page_id", "facebook_page_name", "/facebook/pages");
        copiarPaginaFija(socialAccounts, perfilConPagina, username, "linkedin",
                "linkedin_page_id", "linkedin_page_name", "/linkedin/pages");
        registrarVerificacion(socialAccounts);

        // Qué contestó upload-post, en una línea legible. Es el registro que
        // de verdad hacía falta: dice el perfil consultado y, red por red, si
        // vino conectada o en null. Con eso se distingue "no se conectó" de
        // "se conectó en otro perfil" sin tener que mirar la base.
        log.info("Estado de conexiones del perfil {}: {}", username, resumenDe(socialAccounts));

        // Las que la persona apagó desde Pícale salen de aquí como si no
        // estuvieran conectadas.
        //
        // Sin esto, "Desconectar" no parecía hacer nada: marcaba la fila en
        // la base —y con eso dejaba de publicar, que es lo importante— pero
        // esta respuesta se devolvía tal cual la manda upload-post, que sigue
        // teniendo el token y la sigue dando por conectada. La pantalla se
        // refresca justo con esto al desconectar, así que la red volvía a
        // pintarse en verde en el mismo segundo.
        //
        // Se pone a null en vez de quitar la llave porque null es como el
        // propio upload-post dice "esta red no está conectada", y es lo que
        // la app ya sabe leer.
        for (Platform apagada : accountSync.sync(socialAccounts)) {
            socialAccounts.put(apagada.name().toLowerCase(Locale.ROOT), null);
        }

        return socialAccounts;
    }

    /** "facebook=Solaris Energy, instagram=null, tiktok=pancho_02". */
    @SuppressWarnings("unchecked")
    private String resumenDe(Map<String, Object> socialAccounts) {
        if (socialAccounts.isEmpty()) {
            return "(ninguna)";
        }
        StringBuilder sb = new StringBuilder();
        socialAccounts.forEach((red, valor) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(red).append('=');
            if (!(valor instanceof Map)) {
                sb.append("null");
                return;
            }
            Map<String, Object> cuenta = (Map<String, Object>) valor;
            Object nombre = cuenta.get("page_name");
            if (nombre == null) {
                nombre = cuenta.get("display_name");
            }
            if (nombre == null) {
                nombre = cuenta.get("username");
            }
            sb.append(nombre);
            if (Boolean.TRUE.equals(cuenta.get("reauth_required"))) {
                sb.append(" (necesita reconectar)");
            }
        });
        return sb.toString();
    }

    /**
     * Las páginas de Facebook que administra la cuenta conectada, para elegir
     * en cuál publicar. Facebook no deja publicar en un perfil personal, solo
     * en una Página: sin este paso, upload-post publica en la primera que
     * encuentre, no necesariamente la del negocio.
     */
    public List<Map<String, Object>> facebookPages(Workspace workspace) {
        return listarPaginas(profileOf(workspace), "/facebook/pages");
    }

    /** Igual que {@link #facebookPages}, para organizaciones de LinkedIn. */
    public List<Map<String, Object>> linkedinPages(Workspace workspace) {
        return listarPaginas(profileOf(workspace), "/linkedin/pages");
    }

    /**
     * Fija la Página de Facebook donde deben salir las publicaciones de este
     * workspace, de una vez y para las que sigan.
     */
    public void pinFacebookPage(Workspace workspace, String pageId) {
        fijarPagina(profileOf(workspace), pageId, "/users/facebook-page", "facebook_page_id");
    }

    /** Igual que {@link #pinFacebookPage}, para una organización de LinkedIn. */
    public void pinLinkedinPage(Workspace workspace, String pageId) {
        fijarPagina(profileOf(workspace), pageId, "/users/linkedin-page", "linkedin_page_id");
    }

    // ------------------------------------------------------------------
    // Perfil del workspace en upload-post
    // ------------------------------------------------------------------

    /**
     * Crea el perfil en upload-post si hace falta y lo deja guardado en el
     * workspace, para que {@link UploadPostPublisher} publique con el mismo.
     *
     * <p>Se respeta un {@code uploadPostProfile} que ya estuviera puesto a
     * mano en Ajustes: si alguien dio de alta el perfil con otro nombre, esto
     * no se lo pisa.
     */
    private String ensureProfile(Workspace workspace) {
        String username = profileOf(workspace);
        crearPerfilSiHaceFalta(username);

        if (!username.equals(workspace.getUploadPostProfile())) {
            // Merece un registro: este es el momento en que un workspace
            // queda atado a un perfil de upload-post, y ahi es donde se
            // explican los "conecte las redes y no aparecen" — se conectaron
            // en un perfil y se leen desde otro.
            log.info("Workspace {} ({}) queda atado al perfil de upload-post {}",
                    workspace.getName(), workspace.getId(), username);
            workspace.setUploadPostProfile(username);
            workspaceRepository.save(workspace);
        }
        return username;
    }

    private String profileOf(Workspace workspace) {
        String configurado = workspace.getUploadPostProfile();
        if (configurado != null && !configurado.isBlank()) {
            return configurado;
        }
        UUID id = workspace.getId();
        if (id == null) {
            throw new IllegalStateException("El workspace todavía no tiene id; no se puede conectar nada.");
        }
        return id.toString();
    }

    /**
     * Silencioso a propósito cuando el perfil ya existía: no hay forma de
     * distinguir "ya existe" de otros 4xx sin acoplarse al texto exacto del
     * error, y de todos modos el paso siguiente falla con su propio mensaje
     * claro si el perfil de plano no se pudo crear.
     */
    private void crearPerfilSiHaceFalta(String username) {
        try {
            restClient.post()
                    .uri("/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("username", username))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException ex) {
            log.info("upload-post: no se creó un perfil nuevo para {} ({}); se asume que ya existía",
                    username, ex.getStatusCode());
        } catch (RestClientException ex) {
            log.warn("No se pudo crear el perfil {} en upload-post: {}", username, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Detalles del estado de conexión
    // ------------------------------------------------------------------

    /**
     * El perfil solo trae el id y el nombre de la página fijada, no su foto
     * —esa vive nada más en el listado de páginas—, así que hay que cruzar los
     * dos. El listado se pide solo cuando de verdad hay una página fijada a la
     * cual buscarle foto: sin ese resguardo, cada consulta de estado costaría
     * llamadas extra para las redes que ni usan Página.
     */
    /**
     * Si esta red está conectada, no tiene página fijada y solo administra
     * una, la fija sola.
     *
     * <p>Escribe el id en {@code profile} además de mandarlo, para que la
     * misma consulta que acaba de fijarla ya la devuelva fijada: sin eso, la
     * pantalla tendría que preguntar dos veces para verla, y la primera
     * seguiría diciendo que falta elegir página.
     *
     * <p>Falla en silencio a propósito. Es una comodidad, no un paso del
     * flujo: si upload-post no contesta, queda la elección a mano de siempre,
     * que es exactamente lo que había antes.
     */
    @SuppressWarnings("unchecked")
    private void fijarSiSoloHayUna(
            Map<String, Object> profile, Map<String, Object> socialAccounts, String username,
            String platform, String campoId, String campoNombre, String rutaListado,
            String rutaFijar) {

        if (!(socialAccounts.get(platform) instanceof Map) || profile.get(campoId) != null) {
            return;
        }

        try {
            List<Map<String, Object>> paginas = listarPaginas(username, rutaListado);
            if (paginas.size() != 1) {
                // Ninguna: no hay nada que fijar y el aviso de siempre sigue
                // siendo el correcto. Varias: decide la persona.
                return;
            }

            Map<String, Object> unica = paginas.get(0);
            Object pageId = unica.get("id");
            if (pageId == null) {
                return;
            }

            fijarPagina(username, pageId.toString(), rutaFijar, campoId);
            profile.put(campoId, pageId);
            profile.put(campoNombre, unica.get("name"));

            log.info("Pagina unica de {} fijada sola para {}: {} ({})",
                    platform, username, unica.get("name"), pageId);

        } catch (RuntimeException ex) {
            log.warn("No se pudo fijar sola la pagina de {} para {}: {}",
                    platform, username, ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void copiarPaginaFija(
            Map<String, Object> socialAccounts, Map<String, Object> profile, String username,
            String platform, String campoId, String campoNombre, String rutaListado) {

        Object cuenta = socialAccounts.get(platform);
        Object pageId = profile.get(campoId);
        if (!(cuenta instanceof Map) || pageId == null) {
            return;
        }

        Map<String, Object> cuentaConPagina = new HashMap<>((Map<String, Object>) cuenta);
        cuentaConPagina.put("page_id", pageId);
        cuentaConPagina.put("page_name", profile.get(campoNombre));

        try {
            listarPaginas(username, rutaListado).stream()
                    .filter(pagina -> pageId.equals(pagina.get("id")))
                    .findFirst()
                    .ifPresent(pagina -> cuentaConPagina.put("page_picture", pagina.get("picture")));
        } catch (RuntimeException ex) {
            // Sin la foto la pantalla funciona igual —el nombre ya está—, así
            // que un tropiezo aquí no debe tumbar la consulta de estado.
            log.warn("No se pudo obtener la foto de la página fijada de {} en {}: {}",
                    username, platform, ex.getMessage());
        }

        socialAccounts.put(platform, cuentaConPagina);
    }

    /**
     * Guarda, por red, la última vez que se supo de cierto que seguía sana o
     * desde cuándo dejó de estarlo —lo único real, a falta de una fecha de
     * vencimiento que ninguna red publica de antemano— y lo agrega a la
     * respuesta como {@code last_verified_at}/{@code expired_since}.
     */
    @SuppressWarnings("unchecked")
    private void registrarVerificacion(Map<String, Object> socialAccounts) {
        for (var entry : socialAccounts.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue; // Red no conectada: nada que registrar todavía.
            }
            Map<String, Object> cuenta = new HashMap<>((Map<String, Object>) entry.getValue());
            boolean vencida = Boolean.TRUE.equals(cuenta.get("reauth_required"));

            SocialConnectionCheck check = connectionCheckRepository.findByPlatform(entry.getKey())
                    .orElseGet(() -> SocialConnectionCheck.builder().platform(entry.getKey()).build());

            if (vencida) {
                if (check.getExpiredSince() == null) {
                    check.setExpiredSince(LocalDateTime.now());
                }
            } else {
                check.setLastVerifiedAt(LocalDateTime.now());
                check.setExpiredSince(null);
            }
            connectionCheckRepository.save(check);

            cuenta.put("last_verified_at", check.getLastVerifiedAt());
            cuenta.put("expired_since", check.getExpiredSince());
            socialAccounts.put(entry.getKey(), cuenta);
        }
    }

    // ------------------------------------------------------------------
    // Páginas de Facebook / LinkedIn
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listarPaginas(String username, String path) {
        requireConfigured();

        try {
            Map<?, ?> cuerpo = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(path).queryParam("profile", username).build())
                    .retrieve()
                    .body(Map.class);

            Object pages = cuerpo == null ? null : cuerpo.get("pages");
            return pages instanceof List ? (List<Map<String, Object>>) pages : new ArrayList<>();

        } catch (HttpClientErrorException.NotFound ex) {
            return new ArrayList<>();
        } catch (HttpClientErrorException ex) {
            log.error("upload-post rechazó listar {} de {}: {} {}",
                    path, username, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw errorDeUploadPost(ex, "No se pudieron consultar las páginas disponibles.");
        } catch (RestClientException ex) {
            log.error("No se pudo hablar con upload-post para listar {} de {}: {}", path, username, ex.getMessage());
            throw new IllegalStateException("No se pudo contactar a upload-post.com ahora mismo.");
        }
    }

    private void fijarPagina(String username, String pageId, String path, String campoId) {
        requireConfigured();

        Map<String, Object> body = Map.of(
                "profile_username", username,
                campoId, pageId);

        post(path, body, "No se pudo fijar la página elegida.");
    }

    // ------------------------------------------------------------------
    // Plomería HTTP
    // ------------------------------------------------------------------

    private Map<?, ?> post(String path, Map<String, Object> body, String mensajeGenerico) {
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

        } catch (HttpClientErrorException ex) {
            log.error("upload-post rechazó POST {}: {} {}",
                    path, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw errorDeUploadPost(ex, mensajeGenerico);
        } catch (RestClientException ex) {
            log.error("No se pudo hablar con upload-post en POST {}: {}", path, ex.getMessage());
            throw new IllegalStateException("No se pudo contactar a upload-post.com ahora mismo.");
        }
    }

    /**
     * A dónde manda upload-post el navegador al terminar de conectar.
     *
     * <p>Apunta a una página puente y no directamente a la app por una
     * limitación de los dos lados: upload-post solo acepta un {@code
     * redirect_url} https, y una URL https no puede devolver el control a la
     * app nativa —Android entrega un enlace a una app solo si su esquema está
     * declarado en el manifest—. El resultado era que en móvil el flujo
     * terminaba en el build web y la app nativa se quedaba esperando.
     *
     * <p>La página puente ({@code web/social-connected.html} de metricol_app)
     * recibe el https y rebota a {@code picale://social-connected}. En un
     * navegador de escritorio no intenta el esquema y sigue al build web, así
     * que el mismo enlace sirve para las dos plataformas.
     *
     * <p>La ruta es configurable para poder volver al comportamiento anterior
     * —o apuntar a otra página— sin tocar código.
     */
    private String redirectUrl() {
        String base = Optional.ofNullable(appBaseUrl).orElse("").replaceAll("/$", "");
        return base + redirectPath;
    }

    private void requireConfigured() {
        if (!props.isConfigured()) {
            throw new IllegalStateException(
                    "Falta configurar upload-post.com (variable UPLOAD_POST_API_KEY).");
        }
    }

    /**
     * upload-post limita cuántas peticiones admite por minuto —según el plan— y
     * cuántas publicaciones acepta cada red al día. Sin distinguirlo, un tope
     * alcanzado se ve igual que "no se pudo contactar", y quien lo lea no sabe
     * si reintentar en un minuto sirve de algo.
     */
    private IllegalStateException errorDeUploadPost(HttpClientErrorException ex, String generico) {
        if (ex.getStatusCode().value() == 429) {
            return new IllegalStateException(
                    "upload-post.com está saturado ahora mismo —se llegó al límite de peticiones o de "
                            + "publicaciones del día para esta red—. Espera unos minutos y vuelve a intentar.");
        }
        return new IllegalStateException(generico);
    }
}
