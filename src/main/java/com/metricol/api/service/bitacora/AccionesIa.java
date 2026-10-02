package com.metricol.api.service.bitacora;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Qué significa cada petición de escritura de la API, dicho en una palabra.
 *
 * <p>La bitácora no sabe de servicios: mira método, ruta y cuerpo y los
 * traduce a una acción con nombre ("programó una publicación") y a la entidad
 * sobre la que actuó. Así anotar una acción nueva no toca ningún servicio:
 * basta una fila en esta tabla, y lo que no esté aquí queda como OTRA con su
 * método y su ruta, que siempre se guardan.
 */
public final class AccionesIa {

    private AccionesIa() {
    }

    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern CUENTA_RE = Pattern.compile("^/api/v1/agente/cuentas/(" + UUID_RE.pattern() + ")/");

    /** Lo que se supo de la petición. {@code cuentaId} solo cuando actuó en otra cuenta. */
    public record Clasificacion(String accion, String entidadId, UUID cuentaId) {
    }

    private static final Map<String, String> ETIQUETAS = new LinkedHashMap<>();

    static {
        ETIQUETAS.put("CREAR_BORRADOR", "Creó un borrador");
        ETIQUETAS.put("PROGRAMAR_PUBLICACION", "Programó una publicación");
        ETIQUETAS.put("PUBLICAR_AHORA", "Publicó al instante");
        ETIQUETAS.put("EDITAR_PUBLICACION", "Editó una publicación");
        ETIQUETAS.put("CANCELAR_PUBLICACION", "Canceló una publicación programada");
        ETIQUETAS.put("ARCHIVAR_PUBLICACION", "Archivó una publicación");
        ETIQUETAS.put("RESTAURAR_PUBLICACION", "Restauró una publicación");
        ETIQUETAS.put("ELIMINAR_PUBLICACION", "Eliminó una publicación");
        ETIQUETAS.put("REINTENTAR_PUBLICACION", "Reintentó una publicación");
        ETIQUETAS.put("SUBIR_ARCHIVO", "Subió un archivo a Contenido");
        ETIQUETAS.put("ARCHIVAR_ARCHIVO", "Archivó un archivo");
        ETIQUETAS.put("RESTAURAR_ARCHIVO", "Restauró un archivo");
        ETIQUETAS.put("ELIMINAR_ARCHIVO", "Eliminó un archivo");
        ETIQUETAS.put("LIBERAR_ARCHIVO", "Liberó el espacio de un video");
        ETIQUETAS.put("ACTUALIZAR_MARCA", "Actualizó la marca");
        ETIQUETAS.put("ACTUALIZAR_NEGOCIO", "Actualizó los datos del negocio");
        ETIQUETAS.put("CAMBIAR_CUENTA", "Cambió de cuenta");
        ETIQUETAS.put("GENERAR_CONTENIDO", "Generó contenido con IA");
        ETIQUETAS.put("APROBAR_PROPUESTA", "Aprobó una propuesta del agente");
        ETIQUETAS.put("APROBAR_TODAS", "Aprobó todas las propuestas del agente");
        ETIQUETAS.put("DESCARTAR_PROPUESTA", "Descartó una propuesta del agente");
        ETIQUETAS.put("CAMBIAR_PROPUESTA", "Pidió un cambio en una propuesta");
        ETIQUETAS.put("AGENTE_ENCENDER", "Encendió el agente");
        ETIQUETAS.put("AGENTE_APAGAR", "Apagó el agente");
        ETIQUETAS.put("AGENTE_HORARIO", "Cambió el horario del agente");
        ETIQUETAS.put("AGENTE_PAUSAR", "Puso el agente en pausa de emergencia");
        ETIQUETAS.put("AGENTE_UBICACION", "Cambió la ubicación del negocio");
        ETIQUETAS.put("REVISAR_ARCHIVO", "Pidió al agente revisar un archivo");
        ETIQUETAS.put("REVISAR_AHORA", "Pidió al agente una vuelta de revisión");
        ETIQUETAS.put("DECIDIR_OBSERVACION", "Decidió sobre un archivo en observación");
        ETIQUETAS.put("TEXTO_IA", "Pidió texto a la IA de Pícale");
        ETIQUETAS.put("OTRA", "Otra acción");
    }

    public static String etiqueta(String accion) {
        return ETIQUETAS.getOrDefault(accion, accion);
    }

    public static Clasificacion clasificar(String metodo, String ruta, JsonNode cuerpo) {
        String m = metodo == null ? "" : metodo.toUpperCase();
        String r = ruta == null ? "" : ruta;
        List<String> uuids = uuids(r);
        String entidad = uuids.isEmpty() ? null : uuids.get(uuids.size() - 1);
        UUID cuenta = null;
        Matcher mc = CUENTA_RE.matcher(r);
        if (mc.find()) {
            cuenta = UUID.fromString(mc.group(1));
        }

        String accion = "OTRA";
        if (r.equals("/api/v1/posts") && m.equals("POST")) {
            accion = accionDeGuardado(cuerpo, "CREAR_BORRADOR");
        } else if (r.matches("^/api/v1/posts/[^/]+$") && m.equals("PUT")) {
            accion = accionDeGuardado(cuerpo, "EDITAR_PUBLICACION");
        } else if (r.matches("^/api/v1/posts/[^/]+$") && m.equals("DELETE")) {
            accion = "ARCHIVAR_PUBLICACION";
        } else if (r.endsWith("/cancel") && r.startsWith("/api/v1/posts/")) {
            accion = "CANCELAR_PUBLICACION";
        } else if (r.endsWith("/archive") && r.startsWith("/api/v1/posts/")) {
            accion = m.equals("DELETE") ? "RESTAURAR_PUBLICACION" : "ARCHIVAR_PUBLICACION";
        } else if (r.endsWith("/delete") && r.startsWith("/api/v1/posts/")) {
            accion = "ELIMINAR_PUBLICACION";
        } else if (r.endsWith("/retry") && r.startsWith("/api/v1/posts/")) {
            accion = "REINTENTAR_PUBLICACION";
        } else if (r.equals("/api/v1/media/upload") || r.equals("/api/v1/media/presign") || r.endsWith("/confirm") && r.startsWith("/api/v1/media/")) {
            accion = "SUBIR_ARCHIVO";
        } else if (r.endsWith("/archive") && r.startsWith("/api/v1/media/")) {
            accion = m.equals("DELETE") ? "RESTAURAR_ARCHIVO" : "ARCHIVAR_ARCHIVO";
        } else if (r.endsWith("/liberar") && r.startsWith("/api/v1/media/")) {
            accion = "LIBERAR_ARCHIVO";
        } else if (r.matches("^/api/v1/media/[^/]+$") && m.equals("DELETE")) {
            accion = "ELIMINAR_ARCHIVO";
        } else if (r.equals("/api/v1/workspace/brand")) {
            accion = "ACTUALIZAR_MARCA";
        } else if (r.equals("/api/v1/workspace")) {
            accion = "ACTUALIZAR_NEGOCIO";
        } else if (r.endsWith("/activar") && r.startsWith("/api/v1/workspaces/")) {
            accion = "CAMBIAR_CUENTA";
        } else if (r.equals("/api/v1/content/generate") || r.equals("/api/v1/campaign-images/generate")) {
            accion = "GENERAR_CONTENIDO";
        } else if (r.equals("/api/v1/agente/propuestas/aprobar-todas")) {
            accion = "APROBAR_TODAS";
        } else if (r.endsWith("/aprobar") && r.startsWith("/api/v1/agente/")) {
            accion = "APROBAR_PROPUESTA";
        } else if (r.endsWith("/descartar") && r.startsWith("/api/v1/agente/")) {
            accion = "DESCARTAR_PROPUESTA";
        } else if (r.endsWith("/cambiar") && r.startsWith("/api/v1/agente/propuestas/")) {
            accion = "CAMBIAR_PROPUESTA";
        } else if (r.equals("/api/v1/agente") && m.equals("PUT")) {
            accion = cuerpo != null && cuerpo.path("activo").asBoolean(false) ? "AGENTE_ENCENDER" : "AGENTE_APAGAR";
        } else if (r.equals("/api/v1/agente/horario")) {
            accion = "AGENTE_HORARIO";
        } else if (r.equals("/api/v1/agente/pausar")) {
            accion = "AGENTE_PAUSAR";
        } else if (r.equals("/api/v1/agente/ubicacion")) {
            accion = "AGENTE_UBICACION";
        } else if (r.endsWith("/revisar") && r.startsWith("/api/v1/agente/archivos/")) {
            accion = "REVISAR_ARCHIVO";
        } else if (r.equals("/api/v1/agente/revisar-ahora")) {
            accion = "REVISAR_AHORA";
        } else if (r.endsWith("/decidir") && r.startsWith("/api/v1/agente/archivos/")) {
            accion = "DECIDIR_OBSERVACION";
        } else if (r.startsWith("/api/v1/ai/")) {
            accion = "TEXTO_IA";
        }
        return new Clasificacion(accion, entidad, cuenta);
    }

    /** Guardar una publicación es tres cosas distintas según lo que pida el cuerpo. */
    private static String accionDeGuardado(JsonNode cuerpo, String porOmision) {
        if (cuerpo == null || !cuerpo.isObject()) {
            return porOmision;
        }
        if (cuerpo.path("publishNow").asBoolean(false)) {
            return "PUBLICAR_AHORA";
        }
        if (cuerpo.hasNonNull("scheduledAt") && !cuerpo.get("scheduledAt").asText().isBlank()) {
            return "PROGRAMAR_PUBLICACION";
        }
        return porOmision;
    }

    /**
     * Un resumen legible de lo que pasó, con lo que haya: el texto de la
     * publicación, el archivo, la fecha, el cambio pedido. Para leerlo en una
     * lista, no para reconstruir nada.
     */
    public static String detalle(JsonNode cuerpo, JsonNode resultado) {
        List<String> partes = new ArrayList<>();
        agregar(partes, "texto", texto(resultado, "caption", 140));
        agregar(partes, "archivo", texto(resultado, "fileName", 80));
        agregar(partes, "estado", texto(resultado, "status", 20));
        agregar(partes, "programada", texto(resultado, "scheduledAt", 25));
        agregar(partes, "nombre", texto(resultado, "name", 80));
        agregar(partes, "trabajo", texto(resultado, "jobId", 60));
        if (resultado != null && resultado.isObject() && resultado.has("completitud")) {
            agregar(partes, "marca al", resultado.path("completitud").path("percent").asText() + "%");
        }
        if (cuerpo != null && cuerpo.isObject()) {
            agregar(partes, "cambio", texto(cuerpo, "cambio", 140));
            agregar(partes, "brief", partes.isEmpty() ? texto(cuerpo, "brief", 140) : null);
            if (cuerpo.has("va")) {
                agregar(partes, "decisión", cuerpo.path("va").asBoolean(false) ? "sí va" : "no va");
            }
            if (cuerpo.has("activo") && partes.isEmpty()) {
                agregar(partes, "agente", cuerpo.path("activo").asBoolean(false) ? "encendido" : "apagado");
            }
        }
        String todo = String.join(" · ", partes);
        return todo.isEmpty() ? null : (todo.length() > 500 ? todo.substring(0, 500) : todo);
    }

    private static void agregar(List<String> partes, String etiqueta, String valor) {
        if (valor != null && !valor.isBlank()) {
            partes.add(etiqueta + ": " + valor);
        }
    }

    private static String texto(JsonNode n, String campo, int max) {
        if (n == null || !n.isObject()) {
            return null;
        }
        JsonNode v = n.get(campo);
        if (v == null || v.isNull()) {
            return null;
        }
        String t = v.asText().replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static List<String> uuids(String ruta) {
        List<String> lista = new ArrayList<>();
        Matcher m = UUID_RE.matcher(ruta);
        while (m.find()) {
            lista.add(m.group());
        }
        return lista;
    }
}
