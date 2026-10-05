package com.metricol.api.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.enums.RasgoDelNegocio;

/**
 * Deduce cómo trabaja un negocio ({@link RasgoDelNegocio}) de lo que contó de
 * sí mismo: giro, descripción, qué vende, a quién le habla. Una llamada de
 * texto, una vez por espacio; el dueño lo corrige en Marca.
 */
@Component
public class PerfiladorDelNegocio {

    private static final Logger log = LoggerFactory.getLogger(PerfiladorDelNegocio.class);

    static final String SISTEMA;

    static {
        StringBuilder t = new StringBuilder("""
                Eres un estratega de redes sociales para negocios locales en
                Mexico. Te cuentan de un negocio y dices como trabaja, para saber
                que tipo de contenido le aplica y cual no.

                Contestas SOLO un JSON: {"rasgos": ["CODIGO", ...]}
                con los codigos que SI le aplican, de esta lista:
                """);
        for (RasgoDelNegocio r : RasgoDelNegocio.values()) {
            t.append("- ").append(r.name()).append(": ").append(r.descripcion).append('\n');
        }
        t.append("""

                Criterios:
                - POR_PROYECTO: construccion, remodelacion, instalaciones,
                  carpinteria a la medida, jardineria, diseno, estetica (un antes y
                  despues). No un restaurante ni una tienda.
                - COTIZA: el precio depende de cada trabajo (constructoras,
                  despachos, servicios a la medida). Una taqueria o una tienda no.
                - LOCAL y ATIENDE_ZONA pueden ir juntos.
                - REGULADO: salud, medicamentos, alcohol, finanzas, inversiones,
                  seguros, estetica con procedimientos.
                Elige solo lo que se desprende de lo que te cuentan; ante la duda,
                no lo pongas.
                """);
        SISTEMA = t.toString();
    }

    private final OpenAiClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public PerfiladorDelNegocio(OpenAiClient client) {
        this.client = client;
    }

    /** Los rasgos, o {@code null} si no se pudo (sin IA, sin datos): se intenta otra vez después. */
    public Set<RasgoDelNegocio> deducir(Workspace w) {
        if (w == null || (vacio(w.getGiro()) && vacio(w.getDescripcion()))) {
            return null;
        }
        try {
            String respuesta = client.completeJson(AiOperacion.AGENTE_PERFILAR, SISTEMA, pedido(w));
            return interpretar(respuesta);
        } catch (Exception ex) {
            log.warn("No se pudo deducir el perfil de {}: {}", w.getId(), ex.toString());
            return null;
        }
    }

    static String pedido(Workspace w) {
        StringBuilder t = new StringBuilder("El negocio:\n");
        linea(t, "Nombre", w.getName());
        linea(t, "Giro", w.getGiro());
        linea(t, "Ciudad", w.getCiudad());
        linea(t, "A que se dedica", w.getDescripcion());
        if (w.getBrandProfile() != null) {
            linea(t, "Que vende o destaca", w.getBrandProfile().queVende());
            linea(t, "A quien le habla", w.getBrandProfile().publico());
            linea(t, "Lo que pide evitar", w.getBrandProfile().evitar());
        }
        return t.toString();
    }

    Set<RasgoDelNegocio> interpretar(String respuesta) throws Exception {
        if (respuesta == null) {
            return null;
        }
        int inicio = respuesta.indexOf('{');
        int fin = respuesta.lastIndexOf('}');
        if (inicio < 0 || fin <= inicio) {
            return null;
        }
        JsonNode n = mapper.readTree(respuesta.substring(inicio, fin + 1)).path("rasgos");
        if (!n.isArray()) {
            return null;
        }
        List<String> codigos = new ArrayList<>();
        n.forEach(c -> codigos.add(c.asText("")));
        return RasgoDelNegocio.de(codigos);
    }

    private static void linea(StringBuilder t, String etiqueta, String valor) {
        if (!vacio(valor)) {
            t.append("- ").append(etiqueta).append(": ").append(valor.strip()).append('\n');
        }
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }
}
