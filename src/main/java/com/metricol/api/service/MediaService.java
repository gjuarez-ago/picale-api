package com.metricol.api.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.metricol.api.config.MediaLimitsProperties;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.enums.MediaAssetStatus;
import com.metricol.api.enums.MediaType;
import com.metricol.api.exception.QuotaExceededException;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.MediaPresignRequest;
import com.metricol.api.models.response.MediaAssetResponse;
import com.metricol.api.models.response.MediaPresignResponse;
import com.metricol.api.models.response.StorageUsageResponse;
import com.metricol.api.repository.MediaAssetRepository;
import com.metricol.api.service.media.AdaptadorDeImagenes;
import com.metricol.api.service.media.FfmpegImagen;
import com.metricol.api.service.media.MiniaturasEnSegundoPlano;
import com.metricol.api.service.storage.R2StorageService;
import com.metricol.api.service.storage.StorageQuotaService;

/**
 * Los medios del workspace: subir, medir el espacio y borrar.
 *
 * <p>Hay dos caminos para subir y no es un descuido. El bueno para la app es
 * prefirmar: el archivo va del teléfono a R2 sin pasar por aquí. El de
 * multipart existe para el panel web, que ya tiene el archivo en un navegador
 * de escritorio y para el que tres pasos serían complicar lo simple.
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaAssetRepository repository;
    private final R2StorageService storage;
    private final StorageQuotaService cuota;
    private final MediaLimitsProperties limites;
    private final MiniaturasEnSegundoPlano miniaturas;
    private final com.metricol.api.repository.PostRepository postRepository;
    private final FfmpegImagen ffmpeg;
    private final TransactionTemplate transaccion;

    public MediaService(
            MediaAssetRepository repository,
            R2StorageService storage,
            StorageQuotaService cuota,
            MediaLimitsProperties limites,
            MiniaturasEnSegundoPlano miniaturas,
            com.metricol.api.repository.PostRepository postRepository,
            FfmpegImagen ffmpeg,
            TransactionTemplate transaccion) {
        this.repository = repository;
        this.storage = storage;
        this.cuota = cuota;
        this.limites = limites;
        this.miniaturas = miniaturas;
        this.postRepository = postRepository;
        this.ffmpeg = ffmpeg;
        this.transaccion = transaccion;
    }

    /** La galería: solo lo confirmado. Ver el comentario del repositorio. */
    public List<MediaAssetResponse> list() {
        return repository.findByStatusOrderByCreatedAtDesc(MediaAssetStatus.READY).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Aparta el sitio y devuelve una URL para subir directo a R2.
     *
     * <p>Aquí se valida todo lo que se puede validar sin ver el archivo: el
     * tipo, el tamaño declarado y que quepa en la cuota. La fila nace PENDING
     * y ya cuenta para el espacio usado, que es lo que impide que seis firmas
     * seguidas se pasen del límite entre todas.
     */
    @Transactional
    public MediaPresignResponse presign(MediaPresignRequest request, UUID workspaceId) {
        storage.exigirConfiguracion();

        String contentType = normalizar(request.getContentType());
        exigirTipoDeMedio(contentType);
        cuota.verificar(request.getSizeBytes(), request.getFileName());

        String key = storage.claveNueva(workspaceId, request.getFileName(), contentType);
        String url = storage.urlDe(key);

        MediaAsset asset = repository.save(MediaAsset.builder()
                .fileName(nombreODefecto(request.getFileName(), key))
                .storageKey(key)
                .url(url)
                .type(storage.tipoDe(key, contentType))
                .sizeBytes(request.getSizeBytes())
                .contentType(contentType)
                .status(MediaAssetStatus.PENDING)
                .build());

        return MediaPresignResponse.builder()
                .assetId(asset.getId())
                .uploadUrl(storage.firmarSubida(key, contentType))
                .method("PUT")
                .contentType(contentType)
                .expiresInSeconds(storage.ttlSegundos())
                .url(url)
                .build();
    }

    /**
     * Comprueba contra R2 que el archivo llegó y apunta su tamaño real.
     *
     * <p>Este paso es lo que hace honesta la subida directa. El tamaño que la
     * app declaró al pedir la firma es una promesa; el de aquí es un hecho. Si
     * no cuadra —o si con el tamaño real ya no cabe— el archivo se borra de R2
     * y la fila también: dejar pasar un archivo que rompe la cuota sería
     * ponerle un techo al gasto y luego no aplicarlo.
     */
    public MediaAssetResponse confirm(UUID assetId) {
        MediaAsset asset = repository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Esa subida no existe."));

        if (asset.getStatus() == MediaAssetStatus.READY) {
            // Reintento del cliente: ya estaba confirmada y no hay nada que
            // volver a hacer. Contestar el archivo es mejor que un error por
            // algo que ya salió bien.
            return toResponse(asset);
        }

        R2StorageService.Consulta enR2 = storage.consultar(asset.getStorageKey());
        if (enR2 == null) {
            throw new IllegalStateException(
                    "El archivo no llegó a subirse. Intenta de nuevo.");
        }

        // Antes de dar la subida por buena, y FUERA de la transacción: bajar
        // el archivo y pasarlo por ffmpeg tarda, y hacerlo con una conexión de
        // la base en la mano es lo que congela la aplicación bajo carga. Lo
        // que escribe en la base va después, en `cerrarConfirmacion`.
        enR2 = sanearSiHaceFalta(asset, enR2);

        R2StorageService.Consulta real = enR2;
        return transaccion.execute(estado -> cerrarConfirmacion(assetId, real));
    }

    /**
     * Repara una imagen dañada antes de aceptarla, con ffmpeg.
     *
     * <p>El tamaño dice si el archivo llegó entero desde el teléfono; no dice
     * si el archivo estaba entero en el teléfono. Una foto guardada a medias
     * —una imagen bajada de un chat, por ejemplo— tiene cabecera válida,
     * medidas válidas y sube sin un solo byte de menos, y la rechazan todas
     * las redes al publicar. Ese fue el caso que trajo esto: 44 KB, cuatro
     * redes fallidas, y la persona sin saber por qué.
     *
     * <p>Se decodifica completa. Si ffmpeg se queja, se reencoda como JPEG
     * limpio y se SUSTITUYE en R2 bajo la misma clave: la foto se ve igual y
     * el archivo queda bien formado. Solo si ni eso se puede, se rechaza. Y
     * sin ffmpeg en la máquina no se comprueba nada: la subida sigue como
     * siempre, igual que el adaptador publica sin él.
     *
     * @return lo que hay en R2 al terminar, que puede ser el archivo saneado
     */
    private R2StorageService.Consulta sanearSiHaceFalta(MediaAsset asset, R2StorageService.Consulta enR2) {
        if (asset.getType() != MediaType.IMAGE || !ffmpeg.disponible()) {
            return enR2;
        }

        Path temporal = null;
        try {
            temporal = Files.createTempFile("picale-confirm-", ".img");
            if (!storage.descargar(asset.getStorageKey(), temporal)) {
                return enR2;
            }

            String errores = ffmpeg.verificar(temporal);
            if (errores != null && errores.isBlank()) {
                return enR2;
            }

            byte[] sana = ffmpeg.sanear(temporal);
            if (sana == null) {
                // Ni se pudo leer. Fuera: dejarla pasar es fallar cuatro redes
                // después, cuando la persona ya escribió todo.
                log.warn("Imagen {} irrecuperable ({} bytes): {}", asset.getStorageKey(), enR2.sizeBytes(),
                        errores == null ? "ffmpeg no pudo abrirla" : recortar(errores));
                storage.deleteByKey(asset.getStorageKey());
                transaccion.executeWithoutResult(estado -> repository.deleteById(asset.getId()));
                throw new IllegalArgumentException(
                        "La imagen esta danada o incompleta y no se pudo reparar. Elige otra o vuelve a guardarla.");
            }

            storage.subirBytes(asset.getStorageKey(), sana, "image/jpeg");
            log.info("Imagen {} reparada con ffmpeg: {} -> {} bytes ({})", asset.getStorageKey(),
                    enR2.sizeBytes(), sana.length, errores == null ? "no se podia abrir" : recortar(errores));
            return new R2StorageService.Consulta(sana.length, "image/jpeg");
        } catch (IOException ex) {
            log.warn("No se pudo revisar la imagen {}: {}", asset.getStorageKey(), ex.getMessage());
            return enR2;
        } finally {
            if (temporal != null) {
                try {
                    Files.deleteIfExists(temporal);
                } catch (IOException ignorada) {
                    // Un temporal que se queda no es motivo para fallar la subida.
                }
            }
        }
    }

    private static String recortar(String texto) {
        return texto.length() <= 160 ? texto : texto.substring(0, 160) + "...";
    }

    /** La parte de {@link #confirm} que escribe en la base, corta y con transacción propia. */
    private MediaAssetResponse cerrarConfirmacion(UUID assetId, R2StorageService.Consulta enR2) {
        MediaAsset asset = repository.findById(assetId)
                .orElseThrow(() -> new ResourceNotFoundException("Esa subida no existe."));

        long declarado = asset.getSizeBytes() == null ? 0 : asset.getSizeBytes();
        if (enR2.sizeBytes() > declarado) {
            // Se subió más de lo apartado: la cuota se comprobó contra el
            // tamaño declarado, así que hay que volver a comprobarla con el
            // real. Se descuenta lo que esta misma fila ya tenía apartado
            // para no contarlo dos veces.
            long disponible = cuota.uso().disponible() + declarado;
            if (enR2.sizeBytes() > disponible) {
                log.warn("Subida rechazada: se declararon {} bytes y llegaron {}", declarado,
                        enR2.sizeBytes());
                storage.deleteByKey(asset.getStorageKey());
                repository.delete(asset);
                throw new QuotaExceededException(
                        "STORAGE_QUOTA_EXCEEDED",
                        "El archivo pesa más de lo que cabe en tu espacio. Borra contenido que ya no uses.");
            }
        }

        asset.setSizeBytes(enR2.sizeBytes());
        asset.setStatus(MediaAssetStatus.READY);
        if (enR2.contentType() != null && !enR2.contentType().isBlank()) {
            asset.setContentType(enR2.contentType());
        }
        MediaAsset guardado = repository.save(asset);

        // La miniatura se pide aqui pero se hace en otro hilo: sacar un
        // fotograma tarda un par de segundos y esta respuesta la esta
        // esperando alguien mirando una barra de progreso. Aparecera en el
        // siguiente refresco de la galeria.
        if (guardado.getType() == MediaType.VIDEO) {
            miniaturas.sacar(guardado.getId(), guardado.getTenantId());
        }

        return toResponse(guardado);
    }

    /**
     * Sube un archivo que llegó por multipart. El camino del panel web.
     *
     * <p>Aquí la cuota se comprueba con el tamaño de verdad desde el primer
     * momento, porque el archivo está en la mano. Y se comprueba ANTES de
     * subir: al revés, el archivo que rompe el límite ya estaría pagado y
     * habría que borrarlo a mano de R2, que es el trabajo que nadie hace.
     */
    @Transactional
    public MediaAssetResponse upload(MultipartFile file, UUID workspaceId) {
        exigirTipoDeMedio(normalizar(file.getContentType()));
        cuota.verificar(file.getSize(), file.getOriginalFilename());

        R2StorageService.UploadedFile subido = storage.upload(file, workspaceId);

        MediaAsset asset = MediaAsset.builder()
                .fileName(subido.fileName())
                .storageKey(subido.key())
                .url(subido.url())
                .type(subido.type())
                .sizeBytes(subido.sizeBytes())
                .contentType(subido.contentType())
                // Ya está en R2: no hay hueco entre apartar y subir que
                // justifique un estado intermedio.
                .status(MediaAssetStatus.READY)
                .build();

        return toResponse(repository.save(asset));
    }

    /** Cuánto espacio lleva usado el workspace y cuánto le queda. */
    public StorageUsageResponse usage() {
        StorageQuotaService.Uso uso = cuota.uso();
        return StorageUsageResponse.builder()
                .usedBytes(uso.usado())
                .limitBytes(uso.limite())
                .availableBytes(uso.limite() == null ? null : uso.disponible())
                .percentUsed(uso.porcentaje())
                .usedLabel(MediaLimitsProperties.legible(uso.usado()))
                .limitLabel(uso.limite() == null ? null : MediaLimitsProperties.legible(uso.limite()))
                .maxFileBytes(limites.getMaxFileBytes())
                .maxImagesPerPost(limites.getMaxImagesPerPost())
                .build();
    }

    @Transactional
    public void delete(UUID id) {
        MediaAsset asset = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Archivo no encontrado."));

        // Por clave cuando la hay; recortando la URL solo para las filas
        // anteriores a que la clave se guardara.
        if (asset.getStorageKey() != null && !asset.getStorageKey().isBlank()) {
            storage.deleteByKey(asset.getStorageKey());
        } else {
            storage.delete(asset.getUrl());
        }

        // La miniatura del video se va con él, SALVO que alguna publicación lo
        // siga nombrando.
        //
        // Es lo que pasó en producción: alguien borró un video para hacer
        // sitio y la publicación que ya había salido se quedó sin portada para
        // siempre. El archivo sí tiene que irse —es lo que libera el espacio y
        // lo que se pidió—, pero su fotograma pesa unos cientos de kilobytes
        // contra los cien megas del video, y es lo único que mantiene legible
        // el historial.
        //
        // Cuando no lo usa nadie sí se borra: una miniatura de un video que ya
        // no existe no la va a borrar nadie nunca, porque no queda ninguna
        // fila que sepa que está ahí.
        if (asset.getThumbnailUrl() != null && !asset.getThumbnailUrl().isBlank()
                && !postRepository.algunaPublicacionUsa(asset.getUrl())) {
            storage.delete(asset.getThumbnailUrl());
        }

        // Y las versiones que ffmpeg sacó de esta imagen para encajarla en
        // cada red. Por lo mismo que la miniatura, y con más motivo: de una
        // sola foto puede haber varias, una por cada combinación de redes a la
        // que se publicó, y ninguna tiene fila propia que las recuerde.
        borrarDerivados(asset);

        // La fila después del almacén: si R2 falla, la fila se queda y el
        // espacio sigue contando, que es la verdad. Al revés quedaría un
        // archivo pagándose que ya nadie sabe que existe.
        repository.delete(asset);
    }

    /**
     * Quita de R2 el archivo de un video ya publicado, conservando su fila y
     * su miniatura.
     *
     * <p>No es un borrado: es devolver el espacio. Un video pesa hasta cien
     * megas y se sube para entregárselo a las redes; cuando ya salió, cada red
     * guarda el suyo y el nuestro solo cuesta. Sin esto el espacio de lo
     * publicado no se liberaba nunca, y con videos grandes una cuenta se
     * llenaba en dos publicaciones sin nada que la persona pudiera borrar sin
     * perder su historial.
     *
     * <p><b>Lo que se conserva es lo que hace que no se note:</b> la fila y la
     * miniatura. La publicación sigue enseñando su portada y el botón de verla
     * lleva a la red, donde el video sí está entero.
     *
     * <p>Las versiones adaptadas también se van: se generaron de este archivo
     * y no tienen sentido sin él.
     */
    @Transactional
    public void liberar(UUID id) {
        MediaAsset asset = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Archivo no encontrado."));

        if (asset.getStatus() == MediaAssetStatus.RELEASED) {
            return;
        }

        // El archivo primero y la fila después, igual que en delete: si R2
        // falla, la fila se queda como estaba y el espacio sigue contando,
        // que es la verdad. Al revés quedaría un archivo pagándose que ya
        // nadie sabe que está ahí.
        if (asset.getStorageKey() != null && !asset.getStorageKey().isBlank()) {
            storage.deleteByKey(asset.getStorageKey());
        }
        borrarDerivados(asset);

        // La miniatura NO se toca, a diferencia de delete: es lo único que
        // queda para que la publicación se siga viendo.
        asset.setStatus(MediaAssetStatus.RELEASED);
        repository.save(asset);

        log.info("Video liberado: {} ({})",
                asset.getFileName(),
                MediaLimitsProperties.legible(asset.getSizeBytes() == null ? 0 : asset.getSizeBytes()));
    }

    /**
     * Borra las versiones adaptadas de esta imagen, si las hubo.
     *
     * <p>Silencioso a propósito: son archivos que se pueden volver a generar,
     * así que un fallo de R2 aquí no debe impedir borrar el original, que es
     * lo que la persona pidió y lo único que le libera espacio.
     */
    private void borrarDerivados(MediaAsset asset) {
        if (asset.getStorageKey() == null || asset.getStorageKey().isBlank()
                || asset.getTenantId() == null || asset.getTenantId().isBlank()) {
            return;
        }
        try {
            UUID workspaceId = UUID.fromString(asset.getTenantId());
            int borrados = storage.borrarPrefijo(
                    AdaptadorDeImagenes.prefijoDe(workspaceId, asset.getStorageKey()));
            if (borrados > 0) {
                log.info("Borradas {} versiones adaptadas de {}", borrados, asset.getStorageKey());
            }
        } catch (Exception ex) {
            log.warn("No se pudieron borrar las versiones adaptadas de {}: {}",
                    asset.getStorageKey(), ex.toString());
        }
    }

    /**
     * Solo fotos y videos.
     *
     * <p>Se comprueba aquí y no solo en la app porque el content-type se firma
     * y el cliente lo elige: sin este filtro, la URL firmada serviría para
     * dejar cualquier archivo en el bucket, y el bucket es público.
     */
    private void exigirTipoDeMedio(String contentType) {
        if (contentType == null
                || !(contentType.startsWith("image/") || contentType.startsWith("video/"))) {
            throw new IllegalArgumentException("Solo se pueden subir fotos y videos.");
        }
    }

    /** En minúsculas y sin el "; charset=..." que añaden algunos clientes. */
    private String normalizar(String contentType) {
        if (contentType == null) {
            return null;
        }
        String limpio = contentType.toLowerCase().trim();
        int corte = limpio.indexOf(';');
        return corte > 0 ? limpio.substring(0, corte).trim() : limpio;
    }

    private String nombreODefecto(String fileName, String key) {
        return fileName == null || fileName.isBlank() ? key : fileName;
    }

    private MediaAssetResponse toResponse(MediaAsset asset) {
        return MediaAssetResponse.builder()
                .id(asset.getId())
                .fileName(asset.getFileName())
                .url(asset.getUrl())
                .thumbnailUrl(asset.getThumbnailUrl())
                .type(asset.getType())
                .status(asset.getStatus())
                .sizeBytes(asset.getSizeBytes())
                .sizeLabel(asset.getSizeBytes() == null
                        ? null
                        : MediaLimitsProperties.legible(asset.getSizeBytes()))
                .createdAt(asset.getCreatedAt())
                .build();
    }
}
