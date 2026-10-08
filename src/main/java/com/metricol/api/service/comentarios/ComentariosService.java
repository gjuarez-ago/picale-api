package com.metricol.api.service.comentarios;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.Comentario;
import com.metricol.api.entity.User;
import com.metricol.api.models.response.ComentarioResponse;
import com.metricol.api.enums.Platform;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.ComentarioRepository;
import com.metricol.api.repository.PostTargetRepository;
import com.metricol.api.repository.SocialAccountRepository;
import com.metricol.api.service.social.UploadPostClient;

/**
 * La bandeja de comentarios: traerlos, enseñarlos, contestarlos.
 *
 * <p>Las dos reglas que sostienen todo lo demás:
 *
 * <ul>
 * <li>un comentario se guarda UNA vez (por su id en la red), porque guardarlo
 * dos veces significa avisar dos veces, y eso es exactamente lo que vuelve
 * insoportable una bandeja;
 * <li>lo que escribió la propia cuenta nunca es un pendiente: nadie se avisa a
 * sí mismo.
 * </ul>
 */
@Service
public class ComentariosService {

    private static final Logger log = LoggerFactory.getLogger(ComentariosService.class);

    /** Lo que se pide por página a la red. */
    static final int POR_PAGINA = 50;

    /** Tope de páginas por publicación y vuelta: una publicación viral no se lleva el cupo entero. */
    static final int MAX_PAGINAS = 5;

    private final ComentarioRepository comentarios;
    private final UploadPostClient client;
    private final AvisoDeComentarios aviso;
    private final SocialAccountRepository cuentas;
    private final PostTargetRepository destinos;

    public ComentariosService(ComentarioRepository comentarios, UploadPostClient client,
            @org.springframework.beans.factory.annotation.Autowired(required = false) AvisoDeComentarios aviso,
            @org.springframework.beans.factory.annotation.Autowired(required = false) SocialAccountRepository cuentas,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PostTargetRepository destinos) {
        this.comentarios = comentarios;
        this.client = client;
        this.aviso = aviso;
        this.cuentas = cuentas;
        this.destinos = destinos;
    }

    /**
     * De qué publicación es cada comentario de la lista, en una sola consulta.
     *
     * <p>Sin esto la pantalla enseña un comentario suelto y quien lo lee no
     * sabe a qué le están contestando — que es justo lo que uno necesita saber
     * antes de responder.
     */
    public Map<UUID, ComentarioResponse.Publicacion> publicacionesDe(List<Comentario> lista) {
        if (destinos == null || lista.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = lista.stream().map(Comentario::getPostTargetId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ComentarioResponse.Publicacion> porDestino = new java.util.LinkedHashMap<>();
        for (Object[] fila : destinos.resumenDePublicaciones(ids)) {
            porDestino.put(UUID.fromString(String.valueOf(fila[0])), ComentarioResponse.Publicacion.builder()
                    .miniaturaUrl(fila[1] == null ? null : String.valueOf(fila[1]))
                    .texto(recortar(fila[2] == null ? null : String.valueOf(fila[2]), 140))
                    .enlace(fila[3] == null ? null : String.valueOf(fila[3]))
                    .build());
        }
        return porDestino;
    }

    // ------------------------------------------------------------- traer

    /** Una publicación en una red, de la que hay que traer comentarios. */
    public record Destino(UUID postTargetId, UUID workspaceId, UUID socialAccountId, Platform red,
            String postIdEnLaRed, String perfil, String cuentaPropia) {
    }

    /**
     * Trae los comentarios de una publicación y guarda los que no estaban.
     *
     * <p>Corre dentro del tenant del espacio (lo pone el worker). Devuelve
     * cuántos entraron nuevos; cero es el caso normal y no cuesta nada.
     *
     * @throws org.springframework.web.client.HttpClientErrorException.TooManyRequests
     *         si upload-post pide bajar el ritmo; quien llama corta la vuelta
     */
    @Transactional
    public int traer(Destino destino) {
        if (!CapacidadesPorRed.seLeen(destino.red())) {
            return 0;
        }
        String red = CapacidadesPorRed.comoLaLlamaUploadPost(destino.red());
        int nuevos = 0;
        String cursor = null;
        for (int pagina = 0; pagina < MAX_PAGINAS; pagina++) {
            Map<String, Object> respuesta = client.comentarios(destino.perfil(), red, destino.postIdEnLaRed(),
                    POR_PAGINA, cursor);
            String error = LecturaDeComentarios.error(respuesta);
            if (error != null) {
                log.debug("Sin comentarios de {} en {}: {}", destino.postIdEnLaRed(), red, error);
                anotarSiHayQueReconectar(destino, error);
                return nuevos;
            }
            LecturaDeComentarios.Pagina leida = LecturaDeComentarios.leer(respuesta, destino.cuentaPropia());
            nuevos += guardar(destino, leida.comentarios());
            if (!leida.hayMas()) {
                return nuevos;
            }
            cursor = leida.siguienteCursor();
        }
        return nuevos;
    }

    /**
     * Si la red se quejó de permisos y es de las que piden volver a conectar
     * la cuenta (TikTok, YouTube), lo apunta en la cuenta.
     *
     * <p>Así deja de ser "no hay comentarios" —que es lo que la persona ve, y
     * que no le dice qué hacer— y pasa a ser "vuelve a conectar tu TikTok".
     * Solo se marca cuando el error huele a permiso: una red caída no es
     * motivo para mandar a nadie a reconectar nada.
     */
    void anotarSiHayQueReconectar(Destino destino, String error) {
        if (cuentas == null || destino.socialAccountId() == null
                || !CapacidadesPorRed.pideReconectar(destino.red())) {
            return;
        }
        String e = error.toLowerCase();
        boolean esDePermiso = e.contains("scope") || e.contains("permission") || e.contains("unauthor")
                || e.contains("forbidden") || e.contains("token") || e.contains("reconnect")
                || e.contains("re-auth") || e.contains("insufficient");
        if (esDePermiso) {
            cuentas.marcarComentariosBloqueados(destino.socialAccountId(), LocalDateTime.now());
        }
    }

    /** Las redes de ese espacio que hay que volver a conectar para ver comentarios. */
    public List<Platform> redesPorReconectar(User user) {
        if (cuentas == null) {
            return List.of();
        }
        List<Platform> redes = new ArrayList<>();
        for (String nombre : cuentas.redesPorReconectarParaComentarios(espacioDe(user).toString())) {
            try {
                redes.add(Platform.valueOf(nombre));
            } catch (IllegalArgumentException ignorado) {
                // Una red que ya no manejamos.
            }
        }
        return redes;
    }

    /** Guarda los que no estaban. Devuelve cuántos entraron SIN contar los propios. */
    private int guardar(Destino destino, List<LecturaDeComentarios.Leido> leidos) {
        if (leidos.isEmpty()) {
            return 0;
        }
        List<String> ids = leidos.stream().map(LecturaDeComentarios.Leido::id).toList();
        Set<String> yaEstan = new HashSet<>(comentarios.cualesYaEstan(destino.red().name(), ids));
        LocalDateTime ahora = LocalDateTime.now();
        List<Comentario> aGuardar = new ArrayList<>();
        int nuevos = 0;
        for (LecturaDeComentarios.Leido leido : leidos) {
            if (yaEstan.contains(leido.id())) {
                continue;
            }
            // La misma página puede repetir un id si la red pagina mal.
            yaEstan.add(leido.id());
            aGuardar.add(Comentario.builder()
                    .workspaceId(destino.workspaceId())
                    .socialAccountId(destino.socialAccountId())
                    .postTargetId(destino.postTargetId())
                    .red(destino.red())
                    .idEnLaRed(leido.id())
                    .padreIdEnLaRed(leido.padreId())
                    .autorNombre(recortar(leido.autor(), 255))
                    .autorAvatarUrl(cabe(leido.avatar()))
                    .texto(recortar(leido.texto(), Comentario.MAX_TEXTO))
                    .adjuntoUrl(cabe(leido.adjunto()))
                    .enlace(cabe(leido.enlace()))
                    .escritoEn(leido.escritoEn() == null ? ahora : leido.escritoEn())
                    .traidoEn(ahora)
                    .propio(leido.propio())
                    .postIdEnLaRed(destino.postIdEnLaRed())
                    .build());
            if (!leido.propio()) {
                nuevos++;
            }
        }
        if (!aGuardar.isEmpty()) {
            comentarios.saveAll(aGuardar);
        }
        return nuevos;
    }

    // ------------------------------------------------------------ bandeja

    public Page<Comentario> bandeja(User user, boolean soloPendientes, Platform red, String busca, int pagina,
            int tamano) {
        UUID espacio = espacioDe(user);
        // Alguien está leyendo: mientras tanto, nada de avisos al teléfono.
        if (aviso != null) {
            aviso.estanMirando(espacio);
        }
        String texto = busca == null || busca.isBlank() ? null : "%" + busca.strip().toLowerCase() + "%";
        return comentarios.bandeja(espacio, soloPendientes, red, texto,
                PageRequest.of(Math.max(0, pagina), Math.min(Math.max(1, tamano), 100)));
    }

    public long pendientes(User user) {
        return comentarios.pendientesDe(espacioDe(user));
    }

    public List<Object[]> pendientesPorRed(User user) {
        return comentarios.pendientesPorRed(espacioDe(user));
    }

    public List<Comentario> hilo(User user, UUID id) {
        Comentario c = mio(user, id);
        return comentarios.hilo(espacioDe(user), c.getIdEnLaRed());
    }

    /** Al abrir la bandeja: lo que se vio queda visto. No cambia si está atendido. */
    @Transactional
    public int marcarLeidos(User user, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        UUID espacio = espacioDe(user);
        List<UUID> mios = comentarios.findAllById(ids).stream()
                .filter(c -> espacio.equals(c.getWorkspaceId()))
                .map(Comentario::getId)
                .toList();
        return mios.isEmpty() ? 0 : comentarios.marcarLeidos(mios, LocalDateTime.now());
    }

    // --------------------------------------------------------- contestar

    /**
     * Contesta en la red y lo da por atendido.
     *
     * <p>Si la red rechaza la respuesta, el comentario se queda pendiente y se
     * lanza: lo que no puede pasar es dar por contestado algo que nadie va a
     * leer. Y antes de mandar nada se comprueba que no lo haya atendido ya
     * otra persona, que es lo que pasa cuando dos del equipo abren la bandeja
     * a la vez.
     */
    @Transactional
    public Comentario responder(User user, UUID id, String texto) {
        Comentario c = mio(user, id);
        if (texto == null || texto.isBlank()) {
            throw new IllegalArgumentException("Escribe la respuesta antes de mandarla.");
        }
        if (!CapacidadesPorRed.seContesta(c.getRed())) {
            throw new IllegalArgumentException(c.getRed().getLabel() + " no deja contestar comentarios desde aquí.");
        }
        if (c.getRespondidoEn() != null) {
            throw new IllegalArgumentException("Ese comentario ya lo contestaron.");
        }
        String mensaje = texto.strip();
        if (mensaje.length() > Comentario.MAX_TEXTO) {
            throw new IllegalArgumentException("La respuesta es muy larga. Quítale "
                    + (mensaje.length() - Comentario.MAX_TEXTO) + " caracteres.");
        }
        Map<String, Object> respuesta = client.comentar(perfilDe(user), CapacidadesPorRed.comoLaLlamaUploadPost(
                c.getRed()), c.getPostIdEnLaRed(), c.getIdEnLaRed(), mensaje);
        String error = LecturaDeComentarios.error(respuesta);
        if (error != null) {
            throw new IllegalArgumentException(c.getRed().getLabel()
                    + " no aceptó la respuesta. Inténtalo otra vez en un momento.");
        }
        LocalDateTime ahora = LocalDateTime.now();
        c.setRespuestaTexto(mensaje);
        c.setRespondidoEn(ahora);
        c.setRespuestaIdEnLaRed(idDe(respuesta));
        c.setAtendidoEn(ahora);
        c.setAtendidoPor(user.getId());
        return comentarios.save(c);
    }

    /** Sale de pendientes sin mandar nada a la red. */
    @Transactional
    public Comentario atender(User user, UUID id) {
        Comentario c = mio(user, id);
        if (c.getAtendidoEn() == null) {
            c.setAtendidoEn(LocalDateTime.now());
            c.setAtendidoPor(user.getId());
            comentarios.save(c);
        }
        return c;
    }

    /** Vuelve a pendientes. Es el "Deshacer" de la pantalla. */
    @Transactional
    public Comentario devolverAPendientes(User user, UUID id) {
        Comentario c = mio(user, id);
        if (c.getRespondidoEn() != null) {
            throw new IllegalArgumentException("Ya se mandó la respuesta, no se puede deshacer.");
        }
        c.setAtendidoEn(null);
        c.setAtendidoPor(null);
        return comentarios.save(c);
    }

    /** Oculta en la red lo que es ofensivo o spam. */
    @Transactional
    public Comentario ocultar(User user, UUID id) {
        Comentario c = mio(user, id);
        if (!CapacidadesPorRed.seOculta(c.getRed())) {
            throw new IllegalArgumentException("En " + c.getRed().getLabel() + " no se pueden ocultar comentarios.");
        }
        Map<String, Object> respuesta = client.accionSobreComentario(perfilDe(user),
                CapacidadesPorRed.comoLaLlamaUploadPost(c.getRed()), c.getPostIdEnLaRed(), c.getIdEnLaRed(), "hide");
        if (LecturaDeComentarios.error(respuesta) != null) {
            throw new IllegalArgumentException(c.getRed().getLabel()
                    + " no pudo ocultar el comentario. Inténtalo otra vez en un momento.");
        }
        LocalDateTime ahora = LocalDateTime.now();
        c.setOcultoEn(ahora);
        if (c.getAtendidoEn() == null) {
            c.setAtendidoEn(ahora);
            c.setAtendidoPor(user.getId());
        }
        return comentarios.save(c);
    }

    // ------------------------------------------------------------ apoyos

    private Comentario mio(User user, UUID id) {
        UUID espacio = espacioDe(user);
        return comentarios.findById(id)
                .filter(c -> espacio.equals(c.getWorkspaceId()))
                .orElseThrow(() -> new ResourceNotFoundException("Ese comentario ya no está."));
    }

    private static UUID espacioDe(User user) {
        if (user == null || user.getWorkspace() == null) {
            throw new IllegalArgumentException("Elige un espacio de trabajo primero.");
        }
        return user.getWorkspace().getId();
    }

    /** El perfil en upload-post del espacio; si no tiene, su propio id, como en el resto. */
    private static String perfilDe(User user) {
        String perfil = user.getWorkspace().getUploadPostProfile();
        return perfil == null || perfil.isBlank() ? user.getWorkspace().getId().toString() : perfil.strip();
    }

    private static String idDe(Map<String, Object> respuesta) {
        if (respuesta == null) {
            return null;
        }
        for (String llave : List.of("comment_id", "id")) {
            if (respuesta.get(llave) instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        if (respuesta.get("comment") instanceof Map<?, ?> m && m.get("id") instanceof String s) {
            return s;
        }
        return null;
    }

    private static String recortar(String valor, int tope) {
        if (valor == null) {
            return null;
        }
        String v = valor.strip();
        return v.length() <= tope ? v : v.substring(0, tope);
    }

    /** Una foto que no quepa se pierde sola; nunca tumba el guardado. */
    private static String cabe(String url) {
        return url == null || url.length() > com.metricol.api.entity.SocialAccount.MAX_AVATAR_URL ? null : url;
    }
}
