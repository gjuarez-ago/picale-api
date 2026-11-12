package com.metricol.api.service.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import com.metricol.api.entity.MediaAsset;
import com.metricol.api.entity.User;
import com.metricol.api.exception.ConflictoException;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.MediaPresignRequest;

public final class IdentidadDeSubida {
    private IdentidadDeSubida() {}
    public static UUID usuario(UUID workspace) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null; // Workers internos; endpoints exigen autenticación.
        if (!(auth.getPrincipal() instanceof User u) || u.getId() == null || u.getWorkspace() == null
                || !workspace.equals(u.getWorkspace().getId())) throw noExiste();
        return u.getId();
    }
    public static void acceso(MediaAsset a, UUID workspace) {
        if (!workspace.toString().equals(a.getTenantId())) throw noExiste();
        UUID usuario = usuario(workspace);
        if (usuario != null && a.getUploadOwnerId() != null && !usuario.equals(a.getUploadOwnerId())) throw noExiste();
    }
    public static int version(MediaPresignRequest r, UUID workspace, boolean multipart) {
        int v = r.getUploadProtocolVersion() == null ? 1 : r.getUploadProtocolVersion();
        if (v != 1 && v != 2) throw new IllegalArgumentException("Versión de subida no admitida.");
        if (v == 2 && (!multipart || usuario(workspace) == null || r.getClientUploadId() == null
                || r.getHuellaArchivo() == null || r.getUploadBatchId() == null || r.getUploadBatchIndex() == null)) {
            throw new IllegalArgumentException("La sesión v2 requiere usuario, clave, huella, lote e índice y modo por partes.");
        }
        return v;
    }
    public static String huella(MediaPresignRequest r, UUID ws, boolean multipart, String mime) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            Object[] campos = { version(r, ws, multipart), ws, usuario(ws), multipart, r.getFileName(), mime,
                    r.getSizeBytes(), r.getHuellaArchivo(), r.getUploadBatchId(), r.getUploadBatchIndex() };
            for (Object campo : campos) {
                byte[] bytes = campo == null ? new byte[0] : campo.toString().getBytes(StandardCharsets.UTF_8);
                hash.update((campo == null ? "null:" : bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static void comparar(MediaAsset a, MediaPresignRequest r, UUID ws, boolean multipart, String mime) {
        acceso(a, ws);
        if (a.getUploadIdentityHash() != null && !a.getUploadIdentityHash().equals(huella(r, ws, multipart, mime))) {
            throw new ConflictoException("UPLOAD_ID_CONFLICT", "Esta clave pertenece a otro archivo, usuario, lote o modalidad (identidad incompatible).");
        }
        if (a.getUploadIdentityHash() == null && r.getUploadProtocolVersion() != null && r.getUploadProtocolVersion() == 2) {
            throw new ConflictoException("UPLOAD_LEGACY_SESSION", "Esta sesión es anterior al contrato v2. Conserva su recuperación anterior.");
        }
    }
    public static void guardar(MediaAsset a, MediaPresignRequest r, UUID ws, boolean multipart, String mime) {
        a.setUploadProtocolVersion(version(r, ws, multipart));
        a.setUploadOwnerId(usuario(ws));
        a.setUploadIdentityHash(huella(r, ws, multipart, mime));
        a.setUploadDeclaredBytes(r.getSizeBytes());
    }
    private static ResourceNotFoundException noExiste() { return new ResourceNotFoundException("Esa subida no existe para esta sesión."); }
}
