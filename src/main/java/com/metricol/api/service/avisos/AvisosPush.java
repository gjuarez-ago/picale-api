package com.metricol.api.service.avisos;

import java.io.FileInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.google.auth.oauth2.GoogleCredentials;
import com.metricol.api.entity.Dispositivo;
import com.metricol.api.entity.OrganizationMember;
import com.metricol.api.entity.User;
import com.metricol.api.entity.WorkspaceMember;
import com.metricol.api.enums.Permission;
import com.metricol.api.repository.DispositivoRepository;
import com.metricol.api.repository.OrganizationMemberRepository;
import com.metricol.api.repository.WorkspaceMemberRepository;
import com.metricol.api.repository.WorkspaceRepository;
import com.metricol.api.service.PermissionService;

/**
 * Avisos al teléfono por Firebase Cloud Messaging (API HTTP v1).
 *
 * <p>Sin credenciales (desarrollo, pruebas) no hace nada y lo dice una vez:
 * los avisos son una ayuda, nunca una condición para que algo funcione.
 * Nunca lanza.
 *
 * <p>Quién recibe: quienes pueden aprobar en ese espacio (permiso de
 * programar), incluidos los administradores de la organización.
 */
@Service
public class AvisosPush {

    private static final Logger log = LoggerFactory.getLogger(AvisosPush.class);
    private static final String ALCANCE = "https://www.googleapis.com/auth/firebase.messaging";

    /** {@code app.push.credenciales=adc}: la identidad del propio servidor, sin archivo de llave. */
    static final String IDENTIDAD_DEL_SERVIDOR = "adc";

    private final DispositivoRepository dispositivos;
    private final WorkspaceMemberRepository miembros;
    private final OrganizationMemberRepository orgMiembros;
    private final WorkspaceRepository workspaces;
    private final PermissionService permisos;
    private final RestClient http;

    private final String credencialesRuta;
    private final String proyecto;
    private GoogleCredentials credenciales;
    private boolean avisadoSinCredenciales;

    public AvisosPush(DispositivoRepository dispositivos, WorkspaceMemberRepository miembros,
            OrganizationMemberRepository orgMiembros, WorkspaceRepository workspaces, PermissionService permisos,
            @Value("${app.push.credenciales:}") String credencialesRuta,
            @Value("${app.push.proyecto:picale-dbb27}") String proyecto) {
        this.dispositivos = dispositivos;
        this.miembros = miembros;
        this.orgMiembros = orgMiembros;
        this.workspaces = workspaces;
        this.permisos = permisos;
        this.credencialesRuta = credencialesRuta;
        this.proyecto = proyecto;
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(10));
        fabrica.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().requestFactory(fabrica).build();
    }

    // ------------------------------------------------------------ teléfonos

    /** Guarda (o actualiza) el teléfono de la persona. Si el token era de otra, pasa a ser de esta. */
    public void registrar(User user, String token, String plataforma) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Falta el token del dispositivo.");
        }
        Dispositivo d = dispositivos.findByToken(token.strip()).orElseGet(() -> Dispositivo.builder()
                .token(token.strip()).build());
        d.setUserId(user.getId());
        d.setPlataforma(plataforma == null ? null : plataforma.strip().toUpperCase());
        d.setVistoEn(LocalDateTime.now());
        dispositivos.save(d);
    }

    /** Al cerrar sesión: ese teléfono deja de recibir avisos. Solo el propio. */
    public void quitar(User user, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        dispositivos.findByToken(token.strip())
                .filter(d -> user != null && user.getId().equals(d.getUserId()))
                .ifPresent(dispositivos::delete);
    }

    // ------------------------------------------------------------ envío

    public boolean activo() {
        return credencialesRuta != null && !credencialesRuta.isBlank();
    }

    /**
     * Avisa a quienes pueden aprobar en el espacio.
     *
     * @return si había a quién y se intentó (aunque algún teléfono fallara);
     *         {@code false} sin credenciales o sin teléfonos registrados
     */
    public boolean avisarAlEquipo(UUID workspaceId, String titulo, String cuerpo, Map<String, String> datos) {
        if (!activo()) {
            if (!avisadoSinCredenciales) {
                log.info("Avisos push apagados: falta app.push.credenciales.");
                avisadoSinCredenciales = true;
            }
            return false;
        }
        try {
            Set<UUID> quienes = quienesAprueban(workspaceId);
            List<Dispositivo> telefonos = quienes.isEmpty() ? List.of() : dispositivos.findByUserIdIn(quienes);
            if (telefonos.isEmpty()) {
                return false;
            }
            Map<String, String> todo = new LinkedHashMap<>(datos == null ? Map.of() : datos);
            todo.put("workspaceId", workspaceId.toString());
            for (Dispositivo d : telefonos) {
                enviar(d.getToken(), titulo, cuerpo, todo);
            }
            return true;
        } catch (RuntimeException ex) {
            log.warn("No se pudo avisar al equipo de {}: {}", workspaceId, ex.toString());
            return false;
        }
    }

    /** Miembros del espacio con permiso de programar, y los administradores de su organización. */
    Set<UUID> quienesAprueban(UUID workspaceId) {
        Set<UUID> candidatos = new LinkedHashSet<>();
        for (WorkspaceMember m : miembros.findDelWorkspace(workspaceId)) {
            candidatos.add(m.getUser().getId());
        }
        workspaces.findById(workspaceId).map(w -> w.getOrganization()).ifPresent(org -> {
            for (OrganizationMember om : orgMiembros.findDeLaOrganizacion(org.getId())) {
                if (om.getRole() != null && om.getRole().administraLaOrganizacion()) {
                    candidatos.add(om.getUser().getId());
                }
            }
        });
        candidatos.removeIf(id -> !permisos.permisosDe(id, workspaceId).contains(Permission.POST_SCHEDULE));
        return candidatos;
    }

    private void enviar(String token, String titulo, String cuerpo, Map<String, String> datos) {
        Map<String, Object> mensaje = Map.of("message", Map.of(
                "token", token,
                "notification", Map.of("title", titulo, "body", cuerpo),
                "data", datos,
                "android", Map.of("priority", "high"),
                "apns", Map.of("payload", Map.of("aps", Map.of("sound", "default")))));
        try {
            http.post()
                    .uri("https://fcm.googleapis.com/v1/projects/" + proyecto + "/messages:send")
                    .header("Authorization", "Bearer " + tokenDeAcceso())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mensaje)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException ex) {
            // 404 UNREGISTERED o 400 de token inválido: la app se desinstaló o el token cambió.
            String detalle = ex.getResponseBodyAsString();
            if (ex.getStatusCode().value() == 404 || detalle.contains("UNREGISTERED")
                    || detalle.contains("registration token is not a valid")) {
                dispositivos.deleteByToken(token);
                log.info("Teléfono sin registro en FCM, quitado.");
            } else {
                log.warn("FCM rechazó un aviso ({}): {}", ex.getStatusCode(), detalle);
            }
        } catch (Exception ex) {
            log.warn("No se pudo mandar un aviso: {}", ex.toString());
        }
    }

    /**
     * El token OAuth para FCM; se renueva solo cuando está por vencer.
     *
     * <p>Con {@value #IDENTIDAD_DEL_SERVIDOR} no hay archivo de llave: se usa
     * la cuenta de servicio de la VM (Compute Engine la entrega por su
     * metadata). Es lo que se usa en producción, porque la organización
     * prohíbe crear llaves de cuentas de servicio, y de paso no hay ningún
     * secreto que se pueda filtrar. Con una ruta, se lee el JSON de la llave.
     */
    private synchronized String tokenDeAcceso() throws Exception {
        if (credenciales == null) {
            if (IDENTIDAD_DEL_SERVIDOR.equalsIgnoreCase(credencialesRuta.strip())) {
                credenciales = GoogleCredentials.getApplicationDefault().createScoped(List.of(ALCANCE));
            } else {
                try (InputStream in = new FileInputStream(credencialesRuta)) {
                    credenciales = GoogleCredentials.fromStream(in).createScoped(List.of(ALCANCE));
                }
            }
        }
        credenciales.refreshIfExpired();
        return credenciales.getAccessToken().getTokenValue();
    }
}
