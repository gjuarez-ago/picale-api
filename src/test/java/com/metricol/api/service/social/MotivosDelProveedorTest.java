package com.metricol.api.service.social;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Cómo se le cuenta a la persona lo que dijo el proveedor.
 *
 * <p>El mensaje que llegó a la app en el caso de la foto truncada fue este,
 * en inglés y con correo de soporte incluido. A quien mira su publicación
 * fallida eso no le dice qué hacer.
 */
class MotivosDelProveedorTest {

    private static final String PILLOW = "Invalid image file. Pillow could not decode the payload (43996 bytes). "
            + "Please upload a supported photo format (JPEG, PNG, GIF, WEBP, BMP, or TIFF). "
            + "If this issue persists, please contact support at info@upload-post.com. "
            + "Learn more: https://docs.upload-post.com/resources/common-errors.";

    @Test
    void laFotoDanadaSeDiceEnEspanolYSinSoporte() {
        assertThat(MotivosDelProveedor.traducir("invalid_photo_file", PILLOW, "media_validation"))
                .isEqualTo("La red no pudo leer la imagen: el archivo esta danado o incompleto.");
        // Tambien sin codigo, solo por el texto.
        assertThat(MotivosDelProveedor.traducir(null, PILLOW, null))
                .isEqualTo("La red no pudo leer la imagen: el archivo esta danado o incompleto.");
    }

    @Test
    void elTokenCaducadoMandaAReconectar() {
        assertThat(MotivosDelProveedor.traducir("token_expired", "Access token expired", null))
                .isEqualTo("La conexion con la red caduco. Vuelve a conectarla desde Redes.");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Error validating access token: The session has been invalidated because the user changed their password.",
            "Error validating access token: The user has not authorized application 123456.",
            "(#190) Invalid OAuth 2.0 Access Token",
            "OAuthException: Session has expired on Monday.",
            "(#10) Application does not have permission for this action",
            "(#200) Missing permission instagram_content_publish",
            "The token used in the request has been revoked by the user",
            "Please log in to Instagram and complete the security checkpoint",
            "access_token_invalid: The access token is invalid or not found",
            "User must reauthorize the application",
            "Please reconnect your account" })
    void cadaRedLoDiceDistintoYSeReconoceIgual(String mensaje) {
        assertThat(MotivosDelProveedor.traducir(null, mensaje, null))
                .isEqualTo("La conexion con la red caduco. Vuelve a conectarla desde Redes.");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Caption exceeds 2200 characters.",
            "Video duration is too long for this platform.",
            "Too many requests, rate limit reached." })
    void loQueNoEsDeConexionNoMandaAReconectar(String mensaje) {
        assertThat(MotivosDelProveedor.traducir(null, mensaje, null))
                .isNotEqualTo("La conexion con la red caduco. Vuelve a conectarla desde Redes.");
    }

    @Test
    void unMotivoDesconocidoSeDejaPeroSinLaPublicidad() {
        String motivo = MotivosDelProveedor.traducir(null,
                "Caption exceeds 2200 characters. If this issue persists, please contact support at info@upload-post.com. "
                        + "Learn more: https://docs.upload-post.com/x.", null);
        assertThat(motivo).isEqualTo("Caption exceeds 2200 characters.");
    }

    @Test
    void sinNadaQueDecirQuedaElGenerico() {
        assertThat(MotivosDelProveedor.traducir(null, null, null)).isEqualTo("La red rechazo la publicacion.");
        assertThat(MotivosDelProveedor.traducir(null, "  ", "publish"))
                .isEqualTo("La red rechazo la publicacion (publish).");
    }
}
