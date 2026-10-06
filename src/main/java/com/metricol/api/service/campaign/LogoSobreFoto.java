package com.metricol.api.service.campaign;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.storage.R2StorageService;

/**
 * El logo real del negocio sobre una foto tal cual, para el agente.
 *
 * <p>Es el mismo sello que llevan los diseños de la IA ({@link SelloDeLogo}):
 * el archivo del logo pegado idéntico en el rincón más limpio, nunca
 * redibujado. Aquí se aplica a la foto que subió la persona, sin más cambios.
 *
 * <p>La foto original no se toca: el resultado es un archivo nuevo, marcado
 * como de la IA para que el agente no lo vuelva a revisar como si fuera
 * material del cliente.
 */
@Component
public class LogoSobreFoto {

    private static final Logger log = LoggerFactory.getLogger(LogoSobreFoto.class);

    /** Lo más que se baja para sellar. Una foto de teléfono pesa 3–6 MB. */
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    private final R2StorageService storage;
    private final MediaAssetRepository assets;

    public LogoSobreFoto(R2StorageService storage, MediaAssetRepository assets) {
        this.storage = storage;
        this.assets = assets;
    }

    /**
     * La URL de la copia con logo, o {@code null} si no se pudo (sin logo
     * guardado, un logo que no se lee, R2 que no responde). Nunca lanza: la
     * foto sin logo sigue siendo una propuesta válida.
     */
    public String sellar(MediaAsset foto, String logoUrl, UUID workspaceId) {
        return sellar(foto, logoUrl, workspaceId, null, 0, false);
    }

    /**
     * Con la zona y el tamaño que eligió el director de foto: sin placa si el
     * logo se lee directo sobre la foto (ver {@link SelloDeLogo#colocarSobreFoto}).
     *
     * @param zona     TOP_LEFT… o {@code null} para el sello de siempre (abajo a la derecha, con placa)
     * @param ancho    ancho del logo respecto al de la foto
     * @param historia la foto va de historia: más margen arriba y abajo
     */
    public String sellar(MediaAsset foto, String logoUrl, UUID workspaceId, String zona, double ancho,
            boolean historia) {
        if (logoUrl == null || logoUrl.isBlank() || foto.getStorageKey() == null) {
            return null;
        }
        MediaAsset logo = assets.findByUrlIn(List.of(logoUrl)).stream()
                .filter(a -> a.getType() == MediaType.IMAGE && a.getStorageKey() != null)
                .findFirst().orElse(null);
        if (logo == null) {
            return null;
        }
        try {
            byte[] imagen = bajar(foto);
            byte[] sello = bajar(logo);
            if (imagen == null || sello == null) {
                return null;
            }
            SelloDeLogo.Posicion pedida = SelloDeLogo.Posicion.de(zona);
            byte[] conLogo = pedida == null
                    ? SelloDeLogo.poner(imagen, sello, SelloDeLogo.Posicion.BOTTOM_RIGHT, historia)
                    : SelloDeLogo.colocarSobreFoto(imagen, sello, pedida, ancho, historia).imagen();

            String nombre = "con-logo-" + foto.getFileName();
            String clave = storage.claveNueva(workspaceId, nombre.endsWith(".jpg") ? nombre : nombre + ".jpg",
                    "image/jpeg");
            String url = storage.subirBytes(clave, conLogo, "image/jpeg");
            assets.save(MediaAsset.builder()
                    .fileName(nombre)
                    .storageKey(clave)
                    .url(url)
                    .type(MediaType.IMAGE)
                    .contentType("image/jpeg")
                    .sizeBytes((long) conLogo.length)
                    .status(MediaAssetStatus.READY)
                    .generadaPorIa(true)
                    .build());
            return url;
        } catch (RuntimeException ex) {
            log.warn("No se pudo poner el logo en {}: {}", foto.getId(), ex.getMessage());
            return null;
        }
    }

    /**
     * Lo que dirigió el director de foto para el acabado.
     *
     * @param estilo    LIMPIO, FRANJA o MARCO
     * @param zona      dónde va el logo (TOP_LEFT…)
     * @param anchoLogo ancho del logo respecto a la foto
     * @param encuadre  {x, y, ancho, alto} en proporciones de la foto, o nulo
     * @param rotulo    el texto corto de la franja, o nulo
     * @param negocio   el nombre del negocio, para la franja
     * @param historia  va de historia
     */
    public record Acabado(String estilo, String zona, double anchoLogo, double[] encuadre, String rotulo,
            String negocio, boolean historia) {
    }

    /**
     * La foto con su acabado de marca (ver {@link AcabadoDeMarca}): encuadre,
     * estilo y logo sin fondo. Sin logo (o sin {@code conLogo}) se encuadra y
     * se viste igual. Devuelve la URL de la copia, o {@code null} si no se
     * pudo; nunca lanza.
     */
    public String acabar(MediaAsset foto, String logoUrl, UUID workspaceId, Acabado a, boolean conLogo) {
        if (foto.getStorageKey() == null || a == null) {
            return null;
        }
        try {
            byte[] imagen = bajar(foto);
            if (imagen == null) {
                return null;
            }
            byte[] sello = null;
            if (conLogo && logoUrl != null && !logoUrl.isBlank()) {
                MediaAsset logo = assets.findByUrlIn(List.of(logoUrl)).stream()
                        .filter(l -> l.getType() == MediaType.IMAGE && l.getStorageKey() != null)
                        .findFirst().orElse(null);
                sello = logo == null ? null : bajar(logo);
            }
            AcabadoDeMarca.Encuadre encuadre = a.encuadre() == null || a.encuadre().length != 4 ? null
                    : new AcabadoDeMarca.Encuadre(a.encuadre()[0], a.encuadre()[1], a.encuadre()[2], a.encuadre()[3]);
            SelloDeLogo.Posicion zona = SelloDeLogo.Posicion.de(a.zona());
            byte[] final_ = AcabadoDeMarca.acabar(imagen, sello, new AcabadoDeMarca.Opciones(
                    AcabadoDeMarca.Estilo.de(a.estilo()), zona == null ? SelloDeLogo.Posicion.BOTTOM_RIGHT : zona,
                    a.anchoLogo() <= 0 ? 0.30 : a.anchoLogo(), encuadre, conLogo || "FRASE".equalsIgnoreCase(a.estilo()) ? a.rotulo() : null,
                    conLogo ? a.negocio() : null, a.historia()));

            String nombre = "acabada-" + foto.getFileName();
            String clave = storage.claveNueva(workspaceId, nombre.endsWith(".jpg") ? nombre : nombre + ".jpg",
                    "image/jpeg");
            String url = storage.subirBytes(clave, final_, "image/jpeg");
            assets.save(MediaAsset.builder()
                    .fileName(nombre)
                    .storageKey(clave)
                    .url(url)
                    .type(MediaType.IMAGE)
                    .contentType("image/jpeg")
                    .sizeBytes((long) final_.length)
                    .status(MediaAssetStatus.READY)
                    .generadaPorIa(true)
                    .build());
            return url;
        } catch (RuntimeException ex) {
            log.warn("No se pudo dar el acabado a {}: {}", foto.getId(), ex.getMessage());
            return null;
        }
    }

    private byte[] bajar(MediaAsset asset) {
        if (asset.getSizeBytes() != null && asset.getSizeBytes() > MAX_BYTES) {
            return null;
        }
        Path temporal = null;
        try {
            temporal = Files.createTempFile("picale-sello-", ".img");
            if (!storage.descargar(asset.getStorageKey(), temporal)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(temporal);
            return bytes.length == 0 ? null : bytes;
        } catch (java.io.IOException ex) {
            return null;
        } finally {
            if (temporal != null) {
                try {
                    Files.deleteIfExists(temporal);
                } catch (java.io.IOException ignorada) {
                    // Un temporal suelto no rompe nada.
                }
            }
        }
    }
}
