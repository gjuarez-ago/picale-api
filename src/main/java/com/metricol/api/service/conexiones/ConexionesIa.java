package com.metricol.api.service.conexiones;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.NullNode;
import com.metricol.api.entity.ConexionIa;
import com.metricol.api.entity.User;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.repository.ConexionIaRepository;
import com.metricol.api.service.bitacora.AccionesIa;

/**
 * Las IA conectadas a una persona: darlas de alta cuando el MCP termina el
 * login, listarlas para la pantalla de perfil, desconectarlas, y comprobar
 * en cada llamada que siguen vivas.
 */
@Service
public class ConexionesIa {

    /** El id de la conexión que el MCP manda en cada llamada a la API. */
    public static final String CABECERA_CONEXION = "X-Picale-Conexion";

    /** La "última actividad" se escribe como mucho una vez por minuto, no por llamada. */
    static final Duration PASO_ACTIVIDAD = Duration.ofSeconds(60);

    private final ConexionIaRepository repository;

    public ConexionesIa(ConexionIaRepository repository) {
        this.repository = repository;
    }

    public ConexionIa crear(User persona, String cliente, String clienteId, Long expiraEpoch) {
        String nombre = recorte(cliente == null || cliente.isBlank() ? "Asistente de IA" : cliente.strip(), 80);
        LocalDateTime expira = expiraEpoch == null || expiraEpoch <= 0 ? null
                : LocalDateTime.ofInstant(Instant.ofEpochSecond(expiraEpoch), ZoneId.systemDefault());
        return repository.save(ConexionIa.builder()
                .userId(persona.getId())
                .userEmail(persona.getEmail())
                .cliente(nombre)
                .clienteId(recorte(clienteId, 300))
                .expiraEn(expira)
                .build());
    }

    public List<ConexionIa> listar(User persona) {
        return repository.findByUserIdOrderByCreadaEnDesc(persona.getId());
    }

    public ConexionIa de(User persona, UUID id) {
        return repository.findByIdAndUserId(id, persona.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Esa conexión no existe o no es tuya."));
    }

    /** Desconectar. Repetirlo no cambia nada. */
    public ConexionIa revocar(User persona, UUID id) {
        ConexionIa c = de(persona, id);
        if (c.getRevocadaEn() == null) {
            c.setRevocadaEn(LocalDateTime.now());
            c = repository.save(c);
        }
        return c;
    }

    public int revocarTodas(User persona) {
        int n = 0;
        LocalDateTime ahora = LocalDateTime.now();
        for (ConexionIa c : listar(persona)) {
            if (c.getRevocadaEn() == null) {
                c.setRevocadaEn(ahora);
                repository.save(c);
                n++;
            }
        }
        return n;
    }

    /** La conexión si existe, es de esa persona y sigue viva; vacío si no. */
    public Optional<ConexionIa> activa(UUID id, UUID userId) {
        return repository.findByIdAndUserId(id, userId).filter(c -> c.activa(LocalDateTime.now()));
    }

    /** Anota qué acaba de hacer, sin escribir en cada llamada. */
    public void registrarActividad(ConexionIa c, String metodo, String ruta) {
        LocalDateTime ahora = LocalDateTime.now();
        String accion = etiqueta(metodo, ruta);
        boolean pasoElMinuto = c.getUltimaActividadEn() == null
                || c.getUltimaActividadEn().plus(PASO_ACTIVIDAD).isBefore(ahora);
        boolean cambioLaAccion = accion != null && !accion.equals(c.getUltimaAccion()) && !"GET".equals(metodo);
        if (!pasoElMinuto && !cambioLaAccion) {
            return;
        }
        c.setUltimaActividadEn(ahora);
        if (accion != null) {
            c.setUltimaAccion(accion);
        }
        repository.save(c);
    }

    /** "GET /api/v1/posts" → "Consultó publicaciones"; las escrituras usan la bitácora. */
    public static String etiqueta(String metodo, String ruta) {
        if (ruta == null) {
            return null;
        }
        String corta = ruta.startsWith("/api/v1/") ? ruta.substring("/api/v1/".length()) : ruta;
        int barra = corta.indexOf('/');
        String recurso = barra < 0 ? corta : corta.substring(0, barra);
        int signo = recurso.indexOf('?');
        if (signo >= 0) {
            recurso = recurso.substring(0, signo);
        }
        if ("GET".equals(metodo)) {
            return switch (recurso) {
                case "posts" -> "Consultó publicaciones";
                case "workspace", "workspaces" -> "Consultó la marca";
                case "media" -> "Consultó el contenido";
                case "agente" -> "Revisó el agente";
                case "dashboard" -> "Vio el panel";
                case "bitacora-ia" -> "Consultó la bitácora";
                case "conexiones-ia" -> "Comprobó su conexión";
                case "social-accounts" -> "Consultó las redes";
                case "me" -> "Consultó la cuenta";
                default -> "Consultó " + recurso;
            };
        }
        try {
            String accion = AccionesIa.clasificar(metodo, ruta, NullNode.getInstance()).accion();
            String texto = AccionesIa.etiqueta(accion);
            return texto == null || texto.isBlank() ? metodo + " " + recurso : texto;
        } catch (RuntimeException e) {
            return metodo + " " + recurso;
        }
    }

    private static String recorte(String texto, int max) {
        if (texto == null) {
            return null;
        }
        String t = texto.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
