package com.metricol.api.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.BrandProfile;
import com.metricol.api.entity.User;
import com.metricol.api.entity.Workspace;
import com.metricol.api.enums.TonoDeMarca;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.BrandRequest;
import com.metricol.api.models.response.BrandResponse;
import com.metricol.api.models.response.BrandResponse.Completitud;
import com.metricol.api.repository.WorkspaceRepository;

/**
 * El perfil de marca de un espacio: guardarlo limpio y decir qué tan completo está.
 *
 * <p>Lo que se guarda termina dentro de un prompt de IA, así que se trata como
 * datos no confiables aunque lo escriba el dueño: sin caracteres de control, sin
 * saltos de línea (que en un prompt sirven para «cerrar» un dato y empezar una
 * instrucción) y con topes de largo. Los tonos son una lista cerrada por la misma
 * razón.
 */
@Service
public class BrandService {

    /** Las cosas que cuentan para el porcentaje: todas pesan igual. */
    static final List<String> PARTES = List.of("giro", "ciudad", "descripcion", "objetivo", "queVende", "publico",
            "tono", "contacto");

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]+");
    private static final Pattern WEB = Pattern.compile("^https?://[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(:\\d{2,5})?(/[^\\s]*)?$", Pattern.CASE_INSENSITIVE);

    private final WorkspaceRepository repository;

    public BrandService(WorkspaceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public BrandResponse obtener(User usuario) {
        return respuesta(buscar(usuario));
    }

    @Transactional
    public BrandResponse guardar(User usuario, BrandRequest pedido) {
        Workspace w = buscar(usuario);

        // Los del negocio, con la regla de siempre: nulo = no lo toques; vacío = bórralo.
        if (pedido.getGiro() != null) {
            w.setGiro(texto(pedido.getGiro(), 120));
        }
        if (pedido.getCiudad() != null) {
            w.setCiudad(texto(pedido.getCiudad(), 120));
        }
        if (pedido.getDescripcion() != null) {
            w.setDescripcion(texto(pedido.getDescripcion(), 500));
        }
        if (pedido.getObjetivo() != null) {
            w.setObjetivo(pedido.getObjetivo());
        }

        BrandProfile perfil = new BrandProfile(
                texto(pedido.getQueVende(), 400),
                texto(pedido.getPublico(), 300),
                tonos(pedido.getTono()),
                texto(pedido.getEvitar(), 200),
                whatsapp(pedido.getWhatsapp()),
                web(pedido.getWeb()),
                texto(pedido.getDireccion(), 200));
        w.setBrandProfile(perfil.vacio() ? null : perfil);

        return respuesta(repository.save(w));
    }

    // ------------------------------------------------------------------ cuentas

    /** Qué tan completa está la marca: cada parte pesa lo mismo. */
    static Completitud completitud(Workspace w) {
        BrandProfile p = w.getBrandProfile() == null ? BrandProfile.VACIO : w.getBrandProfile();
        List<String> faltan = new ArrayList<>();
        if (vacio(w.getGiro())) faltan.add("giro");
        if (vacio(w.getCiudad())) faltan.add("ciudad");
        if (vacio(w.getDescripcion())) faltan.add("descripcion");
        if (w.getObjetivo() == null) faltan.add("objetivo");
        if (vacio(p.queVende())) faltan.add("queVende");
        if (vacio(p.publico())) faltan.add("publico");
        if (p.tono() == null || p.tono().isEmpty()) faltan.add("tono");
        if (!p.hayContacto()) faltan.add("contacto");

        int hechas = PARTES.size() - faltan.size();
        return new Completitud(Math.round(hechas * 100f / PARTES.size()), List.copyOf(faltan));
    }

    private static BrandResponse respuesta(Workspace w) {
        BrandProfile p = w.getBrandProfile() == null ? BrandProfile.VACIO : w.getBrandProfile();
        return new BrandResponse(w.getGiro(), w.getCiudad(), w.getDescripcion(), w.getObjetivo(), p.queVende(),
                p.publico(), p.tono() == null ? List.of() : p.tono(), p.evitar(), p.whatsapp(), p.web(), p.direccion(),
                completitud(w));
    }

    // ------------------------------------------------------------------ limpieza

    /** Sin caracteres de control ni saltos, espacios normales, recortado; vacío = nulo. */
    static String texto(String valor, int max) {
        if (valor == null) {
            return null;
        }
        String limpio = CONTROL.matcher(valor).replaceAll(" ").replaceAll("\\s+", " ").strip();
        if (limpio.isEmpty()) {
            return null;
        }
        return limpio.length() > max ? limpio.substring(0, max).strip() : limpio;
    }

    /** Solo tonos que existen, sin repetir, hasta tres. Uno desconocido es un error, no un descarte silencioso. */
    static List<String> tonos(List<String> pedidos) {
        if (pedidos == null || pedidos.isEmpty()) {
            return List.of();
        }
        Set<String> elegidos = new LinkedHashSet<>();
        for (String codigo : pedidos) {
            if (codigo == null || codigo.isBlank()) {
                continue;
            }
            TonoDeMarca tono = TonoDeMarca.de(codigo)
                    .orElseThrow(() -> new IllegalArgumentException("Ese tono no existe: " + codigo.strip() + "."));
            elegidos.add(tono.name());
        }
        if (elegidos.size() > TonoDeMarca.MAXIMO) {
            throw new IllegalArgumentException("Elige hasta " + TonoDeMarca.MAXIMO + " tonos.");
        }
        return List.copyOf(elegidos);
    }

    /**
     * Con lada de país y sin adornos: «999 123 4567» es +529991234567. Diez dígitos se toman como
     * México; con más, se supone que ya traen la lada.
     */
    static String whatsapp(String valor) {
        String digitos = valor == null ? "" : valor.replaceAll("\\D", "");
        if (digitos.isEmpty()) {
            return null;
        }
        if (digitos.length() == 10) {
            return "+52" + digitos;
        }
        if (digitos.length() >= 11 && digitos.length() <= 15) {
            return "+" + digitos;
        }
        throw new IllegalArgumentException("El WhatsApp debe tener 10 dígitos, o traer la lada del país.");
    }

    /** Con https:// y solo http(s): nada de javascript: ni de direcciones a medias. */
    static String web(String valor) {
        String v = texto(valor, 200);
        if (v == null) {
            return null;
        }
        // Un espacio adentro es una dirección a medias ("tacos com.mx"), no algo que se pueda arreglar en silencio.
        String conEsquema = v.matches("(?i)^https?://.*") ? v : "https://" + v;
        if (!WEB.matcher(conEsquema).matches()) {
            throw new IllegalArgumentException("El sitio web no parece una dirección válida: escríbelo como tunegocio.com.");
        }
        return conEsquema;
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    private Workspace buscar(User usuario) {
        return repository.findById(usuario.getWorkspace().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Workspace no encontrado."));
    }
}
