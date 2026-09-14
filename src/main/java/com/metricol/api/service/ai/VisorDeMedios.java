package com.metricol.api.service.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.AiOperacion;
import com.metricol.api.repository.MediaAssetRepository;

/**
 * Mira las imágenes de una publicación y cuenta qué hay en ellas.
 *
 * <p>Lo que devuelve alimenta al que escribe el texto. Sin esto, el modelo
 * escribe a ciegas sobre lo que la persona dictó; con esto puede decir "esos
 * tacos al pastor" en vez de "nuestro producto".
 *
 * <p><b>Se llama en cuanto se agrega la foto, no al publicar.</b> Ese es el
 * truco de velocidad de toda esta función: mirar una imagen tarda un par de
 * segundos, y son segundos que se pueden gastar MIENTRAS la persona todavía
 * está dictando. Cuando suelta el micrófono, esto ya está hecho.
 *
 * <p>Cada archivo se analiza una sola vez en su vida: el resultado se guarda
 * en la fila del propio archivo ({@code MediaAsset.descripcionIa}). Reeditar
 * la publicación, recrear el texto o cambiar de redes no vuelve a pagarlo.
 */
@Service
public class VisorDeMedios {

    private static final Logger log = LoggerFactory.getLogger(VisorDeMedios.class);

    /**
     * Cuántas imágenes se miran como mucho.
     *
     * <p>Tres y no las seis del carrusel: las primeras son las que cuentan la
     * historia —es el orden que eligió la persona— y cada imagen extra suma
     * costo y espera para decir lo mismo con otras palabras.
     */
    private static final int MAXIMO = 3;

    private static final String SISTEMA = """
            Eres un asistente que describe imagenes para ayudar a redactar una
            publicacion de redes sociales.

            Contesta en espanol, en una o dos frases por imagen, diciendo lo
            concreto: que se ve, en que ambiente, que sensacion transmite.
            Nombra lo que se reconozca (un platillo, un producto, un lugar).

            No interpretes ni adornes. No propongas texto de publicacion. No
            digas "la imagen muestra": empieza por lo que hay.
            """;

    private final OpenAiClient client;
    private final MediaAssetRepository repository;

    public VisorDeMedios(OpenAiClient client, MediaAssetRepository repository) {
        this.client = client;
        this.repository = repository;
    }

    /**
     * Describe las imágenes que falten por describir y devuelve todas.
     *
     * <p>Nunca lanza: si la IA no está configurada o falla, se devuelve lo que
     * haya. Escribir el texto sin saber qué hay en la foto sale peor, pero
     * sale; no poder publicar por un adorno no.
     */
    @Transactional
    public List<String> describir(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }

        List<String> mirar = urls.stream().limit(MAXIMO).toList();
        Map<String, MediaAsset> porUrl = new LinkedHashMap<>();
        for (MediaAsset asset : repository.findByUrlIn(mirar)) {
            porUrl.put(asset.getUrl(), asset);
        }

        List<String> yaVistas = new ArrayList<>();
        List<String> pendientes = new ArrayList<>();
        for (String url : mirar) {
            MediaAsset asset = porUrl.get(url);
            String descripcion = asset == null ? null : asset.getDescripcionIa();
            if (descripcion != null && !descripcion.isBlank()) {
                yaVistas.add(descripcion);
            } else {
                pendientes.add(url);
            }
        }

        if (pendientes.isEmpty()) {
            return yaVistas;
        }

        try {
            String respuesta = client.describeImages(AiOperacion.DESCRIBIR_IMAGENES, SISTEMA,
                    pendientes.size() == 1
                            ? "Describe esta imagen."
                            : "Describe estas " + pendientes.size()
                                    + " imagenes, una por linea y en el mismo orden.",
                    pendientes);

            List<String> lineas = repartir(respuesta, pendientes.size());
            for (int i = 0; i < pendientes.size(); i++) {
                String descripcion = lineas.get(i);
                yaVistas.add(descripcion);

                MediaAsset asset = porUrl.get(pendientes.get(i));
                if (asset != null) {
                    asset.setDescripcionIa(recortar(descripcion));
                    repository.save(asset);
                }
            }
        } catch (Exception ex) {
            log.warn("No se pudieron describir {} imagenes: {}", pendientes.size(), ex.toString());
        }

        return yaVistas;
    }

    /**
     * Parte la respuesta en una descripción por imagen.
     *
     * <p>Se le pidió "una por linea", pero un modelo a veces contesta un
     * parrafo o numera las lineas. Si no cuadra el numero, se devuelve el
     * texto entero como una sola descripcion: menos preciso, pero nunca
     * emparejado al reves — que seria peor que no tener nada.
     */
    private static List<String> repartir(String respuesta, int cuantas) {
        List<String> lineas = new ArrayList<>();
        for (String linea : respuesta.split("\\R")) {
            String limpia = linea.strip().replaceFirst("^\\d+[.)-]\\s*", "");
            if (!limpia.isBlank()) {
                lineas.add(limpia);
            }
        }
        if (lineas.size() == cuantas) {
            return lineas;
        }

        List<String> igual = new ArrayList<>();
        String todo = respuesta.strip();
        for (int i = 0; i < cuantas; i++) {
            igual.add(todo);
        }
        return igual;
    }

    /** La columna aguanta 1000; se recorta antes de que lo haga la base. */
    private static String recortar(String texto) {
        return texto.length() <= 1000 ? texto : texto.substring(0, 1000);
    }
}
