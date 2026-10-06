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
import com.metricol.api.enums.RasgoDelNegocio;
import com.metricol.api.service.ai.PerfiladorDelNegocio;
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

    private final PerfiladorDelNegocio perfilador;
    private final com.metricol.api.service.ai.SugerenciaDeMarca sugerencias;
    private final com.metricol.api.service.ai.RevisorDeLogo revisorDeLogo;

    public BrandService(WorkspaceRepository repository) {
        this(repository, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BrandService(WorkspaceRepository repository, PerfiladorDelNegocio perfilador,
            com.metricol.api.service.ai.SugerenciaDeMarca sugerencias,
            com.metricol.api.service.ai.RevisorDeLogo revisorDeLogo) {
        this.repository = repository;
        this.perfilador = perfilador;
        this.sugerencias = sugerencias;
        this.revisorDeLogo = revisorDeLogo;
    }

    /** "Sugerir con IA": la voz de la marca propuesta, sin guardar nada. */
    @Transactional(readOnly = true)
    public com.metricol.api.service.ai.SugerenciaDeMarca.Sugerencia sugerir(User usuario) {
        if (sugerencias == null) {
            throw new IllegalStateException("No pude sugerir ahora.");
        }
        return sugerencias.sugerir(buscar(usuario));
    }

    /**
     * Deduce otra vez cómo trabaja el negocio (botón "Que lo deduzca la IA"):
     * lo deducido reemplaza lo que hubiera y vuelve a ser de la IA.
     */
    @Transactional
    public BrandResponse deducirRasgos(User usuario) {
        Workspace w = buscar(usuario);
        java.util.Set<RasgoDelNegocio> rasgos = perfilador == null ? null : perfilador.deducir(w);
        if (rasgos == null) {
            throw new IllegalStateException("No pude deducir cómo trabaja tu negocio. Intenta de nuevo en un momento.");
        }
        w.setPerfilRasgos(RasgoDelNegocio.guardar(rasgos));
        w.setPerfilRasgosDelDueno(false);
        return respuesta(repository.save(w));
    }

    @Transactional
    public BrandResponse obtener(User usuario) {
        Workspace w = buscar(usuario);
        // La primera vez que se ve este logo, se revisa si es logotipo o foto (y sus colores).
        if (revisorDeLogo != null && w.getLogoUrl() != null && !w.getLogoUrl().equals(w.getLogoRevisado())) {
            revisorDeLogo.revisar(w);
        }
        return respuesta(w);
    }

    @Transactional
    public BrandResponse guardar(User usuario, BrandRequest pedido) {
        Workspace w = buscar(usuario);

        // Los del negocio, con la regla de siempre: nulo = no lo toques; vacío = bórralo. Salvo el
        // giro y la descripción, que son parte del perfil obligatorio: no se pueden dejar vacíos.
        String giroAntes = w.getGiro();
        String descripcionAntes = w.getDescripcion();
        if (pedido.getGiro() != null) {
            w.setGiro(obligatorio(pedido.getGiro(), 120, "El giro"));
        }
        if (pedido.getCiudad() != null) {
            w.setCiudad(texto(pedido.getCiudad(), 120));
        }
        if (pedido.getDescripcion() != null) {
            w.setDescripcion(obligatorio(pedido.getDescripcion(), 500, "La descripción del negocio"));
        }
        if (pedido.getObjetivo() != null) {
            w.setObjetivo(pedido.getObjetivo());
        }

        BrandProfile antes = w.getBrandProfile() == null ? BrandProfile.VACIO : w.getBrandProfile();
        BrandProfile perfil = new BrandProfile(
                texto(pedido.getQueVende(), 400),
                texto(pedido.getPublico(), 300),
                tonos(pedido.getTono()),
                texto(pedido.getEvitar(), 200),
                whatsapp(pedido.getWhatsapp()),
                web(pedido.getWeb()),
                texto(pedido.getDireccion(), 200),
                // La voz de la marca es nueva: quien no la manda (la app instalada, la bienvenida) no la borra.
                pedido.getHistoria() == null ? antes.historia() : texto(pedido.getHistoria(), 600),
                pedido.getValores() == null ? antes.valores() : texto(pedido.getValores(), 300),
                pedido.getFrases() == null ? antes.frases() : lineas(pedido.getFrases(), 800),
                pedido.getPilares() == null ? antes.pilares()
                        : com.metricol.api.enums.PilarDeContenido.de(pedido.getPilares()).stream().map(Enum::name).toList());
        w.setBrandProfile(perfil.vacio() ? null : perfil);

        if (pedido.getRasgos() != null) {
            w.setPerfilRasgos(RasgoDelNegocio.guardar(RasgoDelNegocio.de(pedido.getRasgos())));
            w.setPerfilRasgosDelDueno(true);
        } else if (!Boolean.TRUE.equals(w.getPerfilRasgosDelDueno())
                && (!java.util.Objects.equals(giroAntes, w.getGiro())
                        || !java.util.Objects.equals(descripcionAntes, w.getDescripcion()))) {
            // Cambió a qué se dedica: lo deducido ya no vale y se deduce otra vez.
            w.setPerfilRasgos(null);
        }

        return respuesta(repository.save(w));
    }

    // ------------------------------------------------------------------ cuentas

    /** Qué tan completa está la marca: cada parte pesa lo mismo. */
    public static Completitud completitud(Workspace w) {
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
                completitud(w),
                w.rasgos() == null ? null : w.rasgos().stream().map(Enum::name).toList(),
                Boolean.TRUE.equals(w.getPerfilRasgosDelDueno()),
                p.historia(), p.valores(), p.frases(), p.pilares() == null ? List.of() : p.pilares(),
                Boolean.TRUE.equals(w.getLogoEsFoto()),
                w.getMarcaColores() == null || w.getMarcaColores().isBlank() ? List.of()
                        : List.of(w.getMarcaColores().split(",")));
    }

    // ------------------------------------------------------------------ limpieza

    /** Sin caracteres de control ni saltos, espacios normales, recortado; vacío = nulo. */
    /** Como {@link #texto}, pero conserva los saltos de línea (una frase por línea) y quita las vacías. */
    static String lineas(String valor, int max) {
        if (valor == null) {
            return null;
        }
        String limpio = java.util.Arrays.stream(valor.split("\\R"))
                .map(l -> texto(l, 200))
                .filter(l -> l != null && !l.isBlank())
                .limit(12)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (limpio.isBlank()) {
            return null;
        }
        return limpio.length() <= max ? limpio : limpio.substring(0, max);
    }

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

    /** Como {@link #texto} pero sin admitir vacío: es parte del perfil obligatorio del negocio. */
    static String obligatorio(String valor, int max, String cual) {
        String limpio = texto(valor, max);
        if (limpio == null) {
            throw new IllegalArgumentException(cual + " no puede quedar vacío: es parte del perfil obligatorio del negocio.");
        }
        return limpio;
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
