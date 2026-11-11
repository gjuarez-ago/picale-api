package com.metricol.api.service.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.metricol.api.config.R2Properties;
import com.metricol.api.enums.MediaType;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Todo lo que se habla con Cloudflare R2: firmar una subida, comprobar que
 * llegó, subir a mano y borrar.
 *
 * <p>El camino normal es el prefirmado: la app sube directo al bucket y por la
 * API solo pasan dos peticiones cortas. {@link #upload(MultipartFile)} sigue
 * aquí porque el panel web sube por multipart, y para un archivo que ya está
 * en un navegador de escritorio ese camino es más simple que tres pasos.
 */
@Service
public class R2StorageService {

    /** Lo que acepta {@code DeleteObjects} de una vez. Lo fija el protocolo. */
    private static final int TOPE_BORRADO = 1000;

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final R2Properties props;

    /**
     * Cuánto vale una URL firmada. Media hora y no cinco minutos porque la
     * sube un teléfono: 100 MB por una red mala tardan más de lo que nadie
     * espera, y una URL vencida a mitad de subida obliga a empezar de nuevo.
     */
    @Value("${app.media.presign-ttl-seconds:1800}")
    private long ttlSegundos;

    public R2StorageService(S3Client s3Client, S3Presigner presigner, R2Properties props) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.props = props;
    }

    /** Falla con un mensaje que dice qué variable falta, en vez de un NPE. */
    public void exigirConfiguracion() {
        if (!props.isConfigured()) {
            throw new IllegalStateException(
                    "Falta configurar Cloudflare R2 (variables CLOUDFLARE_R2_ENDPOINT, CLOUDFLARE_R2_ACCESS_KEY_ID, CLOUDFLARE_R2_SECRET_ACCESS_KEY, CLOUDFLARE_R2_BUCKET_NAME, CLOUDFLARE_R2_PUBLIC_URL).");
        }
    }

    /**
     * La ruta donde vivirá el archivo.
     *
     * <p>Lleva el workspace dentro: no es por seguridad —eso lo da el
     * @TenantId de la fila— sino porque el día que haya que mirar en el bucket
     * qué ocupa tanto, o borrar lo de un cliente que se fue, la respuesta se
     * ve en la ruta y no hace falta cruzarla con la base.
     *
     * <p>La extensión la manda el content-type y no el nombre del archivo. Es
     * deliberado: al publicar, que algo sea video se deduce de la extensión de
     * la URL, así que un video llamado "clip" sin extensión —o peor, con
     * ".jpg"— acabaría enviado al endpoint de fotos y rechazado por la red.
     */
    public String claveNueva(UUID workspaceId, String fileName, String contentType) {
        return claveNueva(CARPETA_MEDIA, workspaceId, fileName, contentType);
    }

    /** Lo mismo, bajo otra carpeta raíz del bucket: {@link #CARPETA_LOGOS}, por ejemplo. */
    public String claveNueva(String carpeta, UUID workspaceId, String fileName, String contentType) {
        return carpeta + "/" + workspaceId + "/" + UUID.randomUUID() + extensionPara(fileName, contentType);
    }

    /** Donde va lo que se publica: la biblioteca de Contenido. */
    public static final String CARPETA_MEDIA = "media";

    /**
     * Donde van los logotipos de los espacios, aparte de la biblioteca.
     *
     * <p>Un logotipo no es contenido: no se publica, no sale en la galería y
     * no lo debe tocar la limpieza de archivos sin usar. Vivía en
     * {@code media/} y esa limpieza lo borraba a los tres días por no estar en
     * ninguna publicación.
     */
    public static final String CARPETA_LOGOS = "logos";

    /** La URL pública y permanente de una clave. */
    public String urlDe(String key) {
        return props.getPublicUrl().replaceAll("/$", "") + "/" + key;
    }

    /**
     * Firma un PUT para que la app suba directo.
     *
     * <p>Se firma el content-type, así que la app tiene que mandar exactamente
     * el mismo: es lo que impide que se declare una foto y se suba un
     * ejecutable. El TAMAÑO no se firma —los servicios compatibles con S3 no
     * lo tratan igual— y por eso el tamaño de verdad se comprueba después con
     * {@link #consultar(String)}, que sí es a prueba de todo.
     */
    public String firmarSubida(String key, String contentType) {
        return firmarSubida(key, contentType, ttlSegundos);
    }

    /** Igual, con otra vigencia: la subida en segundo plano puede esperar horas a que Android la corra. */
    public String firmarSubida(String key, String contentType, long segundos) {
        exigirConfiguracion();

        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .contentType(contentType)
                .build();

        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(segundos))
                .putObjectRequest(put)
                .build())
                .url()
                .toString();
    }

    public long ttlSegundos() {
        return ttlSegundos;
    }

    // ------------------------------------------------------------------ subida por partes

    /** Una parte que ya está en R2: su número, su ETag (lo pide R2 para cerrar) y cuánto pesa. */
    public record ParteSubida(int numero, String etag, long bytes) {
    }

    /**
     * Abre una subida por partes y devuelve su id. El content-type se fija aquí: las partes no lo
     * llevan, y el archivo final sale con este.
     */
    public String iniciarPorPartes(String key, String contentType) {
        exigirConfiguracion();
        return s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .contentType(contentType)
                .build())
                .uploadId();
    }

    /**
     * Firma el PUT de una parte. R2 no lo documenta entre sus operaciones firmadas, pero lo acepta:
     * se probó contra el bucket el 27 oct 2026, con el preflight del navegador incluido (ver
     * docs/subida-por-partes.md). Sin cabeceras firmadas: la parte no lleva content-type.
     */
    public String firmarParte(String key, String uploadId, int numero, long ttl) {
        exigirConfiguracion();
        UploadPartRequest parte = UploadPartRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .uploadId(uploadId)
                .partNumber(numero)
                .build();
        return presigner.presignUploadPart(UploadPartPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(ttl))
                .uploadPartRequest(parte)
                .build())
                .url()
                .toString();
    }

    /**
     * Las partes que ya llegaron, en orden. Es lo que permite retomar: el cliente no tiene que
     * recordar nada, R2 lo sabe. Una subida que ya no existe (abortada o vencida) devuelve vacío.
     */
    public List<ParteSubida> partesSubidas(String key, String uploadId) {
        exigirConfiguracion();
        List<ParteSubida> partes = new ArrayList<>();
        Integer desde = null;
        try {
            while (true) {
                ListPartsResponse pagina = s3Client.listParts(ListPartsRequest.builder()
                        .bucket(props.getBucket())
                        .key(key)
                        .uploadId(uploadId)
                        .partNumberMarker(desde)
                        .build());
                for (Part p : pagina.parts()) {
                    partes.add(new ParteSubida(p.partNumber(), p.eTag(), p.size() == null ? 0 : p.size()));
                }
                if (!Boolean.TRUE.equals(pagina.isTruncated())) {
                    break;
                }
                desde = pagina.nextPartNumberMarker();
            }
        } catch (NoSuchUploadException ex) {
            return List.of();
        }
        partes.sort(java.util.Comparator.comparingInt(ParteSubida::numero));
        return partes;
    }

    /** Une las partes en el archivo final. Desde aquí el objeto existe y {@link #consultar} lo ve. */
    public void completarPorPartes(String key, String uploadId, List<ParteSubida> partes) {
        exigirConfiguracion();
        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder()
                        .parts(partes.stream()
                                .map(p -> CompletedPart.builder().partNumber(p.numero()).eTag(p.etag()).build())
                                .toList())
                        .build())
                .build());
    }

    /**
     * Cancela una subida por partes y libera lo que ya ocupaba en R2. Que ya no exista no es un
     * error: es justo lo que se quería.
     */
    public void abortarPorPartes(String key, String uploadId) {
        exigirConfiguracion();
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (NoSuchUploadException ex) {
            // Ya estaba cerrada o la abortó la regla de los 7 días.
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404) {
                throw ex;
            }
        }
    }

    /**
     * Qué hay en el bucket bajo esa clave, o {@code null} si no hay nada.
     *
     * <p>Es la pieza que hace honesta la subida directa: el tamaño que la app
     * declaró al pedir la firma es una promesa, y esto es el hecho. Sin esta
     * comprobación, la cuota de espacio la decidiría el cliente.
     */
    public Consulta consultar(String key) {
        exigirConfiguracion();
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .build());
            return new Consulta(head.contentLength(), head.contentType());
        } catch (NoSuchKeyException ex) {
            return null;
        } catch (S3Exception ex) {
            // R2 contesta 404 sin cuerpo de NoSuchKey en algunos casos; se
            // trata igual que "no está", que es lo que significa.
            if (ex.statusCode() == 404) {
                return null;
            }
            throw ex;
        }
    }

    /**
     * Sube un archivo que ya está en este servidor.
     *
     * <p>Es el camino del panel web, que manda el archivo por multipart. Para
     * un navegador de escritorio con buena conexión eso es más simple que
     * firmar, subir y confirmar, y el tamaño lo ve el servidor. Lo que NO debe
     * usar este camino es la app: ahí los archivos son grandes, la red mala, y
     * el intermediario cuesta.
     */
    public UploadedFile upload(MultipartFile file, java.util.UUID workspaceId) {
        return upload(file, workspaceId, CARPETA_MEDIA);
    }

    /** Lo mismo, bajo otra carpeta raíz del bucket. */
    public UploadedFile upload(MultipartFile file, java.util.UUID workspaceId, String carpeta) {
        exigirConfiguracion();
        if (file.isEmpty()) {
            throw new IllegalArgumentException("El archivo está vacío.");
        }

        String originalName = file.getOriginalFilename();
        String key = claveNueva(carpeta, workspaceId, originalName, file.getContentType());

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo subir el archivo a R2.", ex);
        }

        return new UploadedFile(
                originalName != null ? originalName : key,
                key,
                urlDe(key),
                tipoDe(key, file.getContentType()),
                file.getSize(),
                file.getContentType());
    }

    /**
     * ¿Esta URL apunta a NUESTRO bucket?
     *
     * <p>Se pregunta antes de descargar nada para adaptarlo. Hoy
     * {@code POST /posts} acepta cualquier URL de medio, así que sin esta
     * comprobación bastaría con guardar un post apuntando a una dirección
     * interna para que el servidor se la descargara y la procesara. Lo que no
     * es nuestro se publica tal cual: no se toca y no se visita.
     */
    public boolean esNuestra(String url) {
        return url != null && props.isConfigured() && url.startsWith(prefijoPublico());
    }

    /** La clave que hay detrás de una URL pública nuestra, o {@code null}. */
    public String claveDe(String url) {
        if (!esNuestra(url)) {
            return null;
        }
        String clave = url.substring(prefijoPublico().length());
        // Una clave con ".." se saldria del prefijo del workspace al
        // construirse la de destino. No deberia pasar —las claves las genera
        // esta misma clase— pero la URL llega desde fuera.
        return clave.isBlank() || clave.contains("..") ? null : clave;
    }

    /** Baja el objeto a un archivo del disco. Devuelve si se pudo. */
    public boolean descargar(String key, Path destino) {
        exigirConfiguracion();
        try {
            // No se usa el atajo `getObject(request, Path)`: escribe con
            // CREATE_NEW y aqui el destino ya existe, porque quien llama lo
            // aparta antes con `Files.createTempFile`. El objeto se bajaba
            // entero -la respuesta era 200- y reventaba al guardarlo, que se
            // leia como si R2 hubiera contestado mal.
            try (ResponseInputStream<GetObjectResponse> cuerpo = s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(key)
                            .build())) {

                Files.copy(cuerpo, destino, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (S3Exception ex) {
            throw new IllegalStateException("No se pudo descargar " + key + " de R2", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo guardar " + key + " en disco", ex);
        }
    }

    /** Sube bytes que generó el servidor, no una persona. */
    public String subirBytes(String key, byte[] contenido, String contentType) {
        exigirConfiguracion();
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .contentType(contentType)
                .build(), RequestBody.fromBytes(contenido));
        return urlDe(key);
    }

    /**
     * Sube un archivo generado sin cargar el video completo en el heap.
     */
    public String subirArchivo(String key, Path archivo, String contentType) {
        exigirConfiguracion();
        try {
            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .contentType(contentType)
                    .build(), RequestBody.fromFile(archivo));
            return urlDe(key);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("No se pudo subir el archivo generado a R2.", ex);
        }
    }

    private String prefijoPublico() {
        return props.getPublicUrl().replaceAll("/$", "") + "/";
    }

    /** Borra por clave. Es el camino bueno desde que la clave se guarda. */
    public void deleteByKey(String key) {
        if (key == null || key.isBlank() || !props.isConfigured()) {
            return;
        }
        s3Client.deleteObject(builder -> builder.bucket(props.getBucket()).key(key));
    }

    /**
     * Todas las claves que cuelgan de un prefijo.
     *
     * <p>Pagina hasta el final a propósito: {@code ListObjectsV2} devuelve mil
     * como mucho, y quedarse con la primera página haría una limpieza que
     * parece funcionar y deja atrás todo lo que pase de mil — el peor tipo de
     * limpieza, la que hay que volver a hacer sin saberlo.
     */
    public List<String> listarClaves(String prefijo) {
        return listarObjetos(prefijo).stream().map(ObjetoGuardado::clave).toList();
    }

    /** Un objeto del bucket: su clave, cuándo se escribió y cuánto pesa. */
    public record ObjetoGuardado(String clave, java.time.Instant modificado, long bytes) {
    }

    /** Como {@link #listarClaves}, con la fecha y el tamaño de cada objeto. */
    public List<ObjetoGuardado> listarObjetos(String prefijo) {
        if (prefijo == null || prefijo.isBlank() || !props.isConfigured()) {
            return List.of();
        }

        List<ObjetoGuardado> claves = new ArrayList<>();
        String token = null;
        do {
            final String continuacion = token;
            ListObjectsV2Response respuesta = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(props.getBucket())
                    .prefix(prefijo)
                    .continuationToken(continuacion)
                    .build());
            respuesta.contents().forEach(objeto -> claves.add(new ObjetoGuardado(objeto.key(),
                    objeto.lastModified(), objeto.size() == null ? 0 : objeto.size())));
            token = Boolean.TRUE.equals(respuesta.isTruncated()) ? respuesta.nextContinuationToken() : null;
        } while (token != null);

        return claves;
    }

    /**
     * Borra todo lo que cuelga de un prefijo. Devuelve cuántos objetos cayeron.
     *
     * <p>De mil en mil, que es el tope de {@code DeleteObjects}: uno por
     * llamada sería una petición de red por archivo, y la barrida de un
     * workspace con miles de derivados tardaría más de lo que dura la ventana
     * de madrugada en la que corre.
     */
    public int borrarPrefijo(String prefijo) {
        List<String> claves = listarClaves(prefijo);
        if (claves.isEmpty()) {
            return 0;
        }
        borrarClaves(claves);
        return claves.size();
    }

    /** Borra una lista de claves, en lotes de mil. */
    public void borrarClaves(List<String> claves) {
        if (claves == null || claves.isEmpty() || !props.isConfigured()) {
            return;
        }
        for (int desde = 0; desde < claves.size(); desde += TOPE_BORRADO) {
            List<ObjectIdentifier> lote = claves
                    .subList(desde, Math.min(desde + TOPE_BORRADO, claves.size()))
                    .stream()
                    .map(clave -> ObjectIdentifier.builder().key(clave).build())
                    .toList();

            s3Client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(props.getBucket())
                    .delete(Delete.builder().objects(lote).build())
                    .build());
        }
    }

    /**
     * Borra deduciendo la clave de la URL pública.
     *
     * <p>Solo para las filas anteriores a que se guardara la clave. Si la URL
     * no empieza por el prefijo público —porque se cambió el dominio— no
     * borra nada en vez de inventar una clave y borrar algo ajeno.
     */
    public void delete(String url) {
        if (url == null || !props.isConfigured()) {
            return;
        }
        String prefix = prefijoPublico();
        if (!url.startsWith(prefix)) {
            return;
        }
        deleteByKey(url.substring(prefix.length()));
    }

    /** IMAGE o VIDEO, según el content-type y, si no viene, la extensión. */
    public MediaType tipoDe(String fileNameOKey, String contentType) {
        if (contentType != null && contentType.toLowerCase().startsWith("video/")) {
            return MediaType.VIDEO;
        }
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return MediaType.IMAGE;
        }
        return esExtensionDeVideo(extensionDe(fileNameOKey)) ? MediaType.VIDEO : MediaType.IMAGE;
    }

    /**
     * La extensión con la que se guarda: la del nombre si concuerda con el
     * content-type, y si no la que dicta el content-type. Ver la nota de
     * {@link #claveNueva}: de esta extensión depende que al publicar un video
     * se reconozca como video.
     */
    private String extensionPara(String fileName, String contentType) {
        String extension = extensionDe(fileName);
        boolean videoPorTipo = contentType != null
                && contentType.toLowerCase().startsWith("video/");

        if (videoPorTipo) {
            return esExtensionDeVideo(extension) ? extension : porContentType(contentType, ".mp4");
        }
        return esExtensionDeImagen(extension) ? extension : porContentType(contentType, ".jpg");
    }

    /** ".jpg" a partir de "image/jpeg". Con un subtipo raro cae al respaldo. */
    private String porContentType(String contentType, String porDefecto) {
        if (contentType == null || !contentType.contains("/")) {
            return porDefecto;
        }
        String subtipo = contentType.substring(contentType.indexOf('/') + 1).toLowerCase();
        // El punto y coma aparece en "image/jpeg; charset=..." que mandan
        // algunos clientes.
        int corte = subtipo.indexOf(';');
        if (corte > 0) {
            subtipo = subtipo.substring(0, corte).trim();
        }
        return switch (subtipo) {
            case "jpeg", "jpg" -> ".jpg";
            case "png" -> ".png";
            case "webp" -> ".webp";
            case "gif" -> ".gif";
            case "heic", "heif" -> ".heic";
            case "mp4" -> ".mp4";
            case "quicktime", "mov" -> ".mov";
            case "webm" -> ".webm";
            default -> porDefecto;
        };
    }

    private String extensionDe(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
    }

    private boolean esExtensionDeVideo(String extension) {
        return extension.equals(".mp4") || extension.equals(".mov") || extension.equals(".webm");
    }

    private boolean esExtensionDeImagen(String extension) {
        return extension.equals(".jpg") || extension.equals(".jpeg")
                || extension.equals(".png") || extension.equals(".webp")
                || extension.equals(".gif") || extension.equals(".heic");
    }

    /** Lo que R2 dice que hay guardado bajo una clave. */
    public record Consulta(long sizeBytes, String contentType) {
    }

    public record UploadedFile(
            String fileName, String key, String url, MediaType type,
            long sizeBytes, String contentType) {
    }
}
