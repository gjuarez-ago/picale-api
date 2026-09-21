package com.metricol.api.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.metricol.api.service.auth.EmailService.Correo;

import jakarta.mail.internet.MimeMessage;

/** Los correos de Pícale: una sola imagen, sin contraseñas, y a salvo de lo que escriba una persona. */
class EmailServiceTest {

    private static final String SITIO = "https://picale.rodtech.cloud";
    private static final String SOPORTE = "contacto@rodtech.cloud";

    private static Correo bienvenida(boolean google) {
        return EmailService.bienvenida("Ana Pérez", "ana@ejemplo.com", google, SITIO, SOPORTE);
    }

    private static Correo recuperacion() {
        return EmailService.recuperacion("482913", "Ana Pérez", "ana@ejemplo.com", 15, SITIO, SOPORTE);
    }

    private static Correo invitacion() {
        return EmailService.invitacion("Luis Soto", "Agencia Sol", SITIO + "/invitacion?t=abc", 7, SITIO, SOPORTE);
    }

    // ------------------------------------------------------------------ la imagen es una sola

    @Test
    @DisplayName("todos los correos usan la misma tipografía de la marca, con una de respaldo parecida")
    void tipografia() {
        for (Correo c : java.util.List.of(bienvenida(true), bienvenida(false), recuperacion(), invitacion())) {
            assertThat(c.html())
                    .contains("'Manrope',-apple-system")
                    .contains("fonts.googleapis.com/css2?family=Manrope")
                    .contains("'Segoe UI'");
        }
    }

    @Test
    @DisplayName("todos llevan el logotipo real del sitio, la franja de la marca, la ayuda y los enlaces legales")
    void marco() {
        for (Correo c : java.util.List.of(bienvenida(true), recuperacion(), invitacion())) {
            assertThat(c.html())
                    .contains("src=\"" + SITIO + "/logotipo-navbar.png\"")
                    .contains("alt=\"Pícale\"")
                    .contains("linear-gradient(90deg,#28C9FD")
                    .contains("mailto:" + SOPORTE)
                    .contains(SITIO + "/privacidad")
                    .contains(SITIO + "/terminos")
                    .contains("Rodtech Solutions, S.A. de C.V.");
        }
    }

    @Test
    @DisplayName("piensan en el celular y en el modo oscuro, y traen un preencabezado")
    void adaptables() {
        String html = bienvenida(true).html();
        assertThat(html).contains("name=\"viewport\"").contains("max-width:560px").contains("@media (max-width:560px)")
                .contains("prefers-color-scheme:dark").contains("color-scheme");
        assertThat(html).contains("Tu cuenta ya está lista. Así empiezas.");
    }

    @Test
    @DisplayName("todos traen su versión en texto plano, sin etiquetas, con el mismo mensaje")
    void textoPlano() {
        for (Correo c : java.util.List.of(bienvenida(false), recuperacion(), invitacion())) {
            assertThat(c.texto()).doesNotContain("<").doesNotContain("&amp;").contains(SOPORTE).contains(SITIO + "/privacidad");
        }
        assertThat(recuperacion().texto()).contains("482913").contains("15 minutos");
        assertThat(invitacion().texto()).contains(SITIO + "/invitacion?t=abc");
    }

    // ------------------------------------------------------------------ la bienvenida

    @Test
    @DisplayName("la bienvenida nunca lleva una contraseña, y dice que nunca se pedirá por correo")
    void sinContrasena() {
        for (boolean google : new boolean[] { true, false }) {
            Correo c = bienvenida(google);
            assertThat(c.html() + c.texto()).contains("nunca te pediremos tu contraseña por correo");
            // No hay forma de que lleve una: el método ni siquiera la recibe. Y no lo insinúa.
            assertThat(c.html() + c.texto()).doesNotContainIgnoringCase("tu contraseña es")
                    .doesNotContainIgnoringCase("contraseña temporal").doesNotContainIgnoringCase("tu clave");
        }
    }

    @Test
    @DisplayName("con Google dice que no necesita contraseña y cómo ponerse una; con formulario, cuál es su usuario")
    void segunComoEntro() {
        Correo google = bienvenida(true);
        assertThat(google.html()).contains("no necesitas contraseña").contains("Olvidé mi contraseña");
        assertThat(google.texto()).contains("Entraste con tu cuenta de Google (ana@ejemplo.com)").contains("Olvidé mi contraseña");
        assertThat(google.html()).doesNotContain("Tu usuario es");

        Correo formulario = bienvenida(false);
        assertThat(formulario.html()).contains("Tu usuario es").contains("ana@ejemplo.com");
        assertThat(formulario.html()).doesNotContain("no necesitas contraseña");
    }

    @Test
    @DisplayName("la bienvenida dice qué sigue (marca, redes, primera publicación) y lleva al panel")
    void queSigue() {
        Correo c = bienvenida(true);
        assertThat(c.html()).contains("Cuéntanos de tu negocio").contains("Conecta tus redes").contains("Crea tu primera publicación")
                .contains("href=\"" + SITIO + "/panel\"").contains("Ir a mi panel");
        assertThat(c.asunto()).isEqualTo("Ana Pérez, te damos la bienvenida a Pícale");
        assertThat(EmailService.bienvenida(null, "ana@ejemplo.com", true, SITIO, SOPORTE).asunto()).isEqualTo("Te damos la bienvenida a Pícale");
    }

    // ------------------------------------------------------------------ recuperación e invitación

    @Test
    @DisplayName("la recuperación pone el código en el asunto, en el cuerpo y cuándo vence, sin botón")
    void recuperacionDice() {
        Correo c = recuperacion();
        assertThat(c.asunto()).isEqualTo("482913 es tu código para restablecer tu contraseña");
        assertThat(c.html()).contains(">482913<").contains("El código vence en 15 minutos").contains("nunca compartas el código");
    }

    @Test
    @DisplayName("la invitación dice quién invita y a qué, y su botón lleva al enlace")
    void invitacionDice() {
        Correo c = invitacion();
        assertThat(c.asunto()).isEqualTo("Luis Soto te invitó a Agencia Sol en Pícale");
        assertThat(c.html()).contains("Te invitaron a Agencia Sol").contains("href=\"" + SITIO + "/invitacion?t=abc\"")
                .contains("Aceptar la invitación").contains("El enlace vence en 7 días.");
    }

    // ------------------------------------------------------------------ seguridad

    @Test
    @DisplayName("lo que escribe una persona (nombre, organización) no rompe el HTML ni inyecta nada")
    void escapa() {
        String malo = "<script>alert(1)</script>\"><img src=x onerror=alert(1)>";

        assertThat(EmailService.bienvenida(malo, "a@b.com", true, SITIO, SOPORTE).html())
                .doesNotContain("<script>").doesNotContain("<img src=x").contains("&lt;script&gt;");
        assertThat(EmailService.invitacion(malo, malo, SITIO + "/x", 7, SITIO, SOPORTE).html())
                .doesNotContain("<script>").doesNotContain("<img src=x").contains("&lt;script&gt;");
        assertThat(EmailService.recuperacion("123456", malo, "a@b.com", 15, SITIO, SOPORTE).html())
                .doesNotContain("<script>").doesNotContain("<img src=x");
    }

    @Test
    @DisplayName("un salto de línea en el nombre no puede colar otra cabecera en el asunto")
    void asuntoDeUnaLinea() {
        Correo c = EmailService.bienvenida("Ana\r\nBcc: victima@ejemplo.com", "a@b.com", true, SITIO, SOPORTE);
        assertThat(c.asunto()).doesNotContain("\r").doesNotContain("\n");

        Correo i = EmailService.invitacion("Luis\nBcc: x@y.com", "Agencia\r\nSol", SITIO + "/x", 7, SITIO, SOPORTE);
        assertThat(i.asunto()).doesNotContain("\r").doesNotContain("\n");
        assertThat(EmailService.enUnaLinea("a\r\nb\tc")).isEqualTo("a b c");
    }

    // ------------------------------------------------------------------ envío

    private static EmailService servicio(JavaMailSender sender, String llave, String sitio) {
        return new EmailService(sender, "notificaciones@picale.rodtech.cloud", llave, sitio, SOPORTE);
    }

    @Test
    @DisplayName("con la llave puesta manda un correo de dos partes (texto y HTML), del remitente de Pícale")
    void envia() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());

        servicio(sender, "re_llave", SITIO + "/").enviarBienvenida("ana@ejemplo.com", "Ana Pérez", true);

        ArgumentCaptor<MimeMessage> enviado = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(enviado.capture());
        MimeMessage m = enviado.getValue();
        m.saveChanges();
        assertThat(m.getSubject()).isEqualTo("Ana Pérez, te damos la bienvenida a Pícale");
        jakarta.mail.internet.InternetAddress de = (jakarta.mail.internet.InternetAddress) m.getFrom()[0];
        assertThat(de.getAddress()).isEqualTo("notificaciones@picale.rodtech.cloud");
        assertThat(de.getPersonal()).isEqualTo("Pícale");
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("ana@ejemplo.com");
        assertThat(m.getContentType()).containsIgnoringCase("multipart");
        // La diagonal final del sitio no se duplica en los enlaces.
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        m.writeTo(bytes);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("text/plain").contains("text/html").doesNotContain(".cloud//");
    }

    @Test
    @DisplayName("sin la llave no se manda nada (y no truena)")
    void sinLlaveNoEnvia() {
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailService s = servicio(sender, "", SITIO);

        s.enviarBienvenida("ana@ejemplo.com", "Ana", false);
        s.enviarCodigoDeRecuperacion("ana@ejemplo.com", "Ana", "123456", 15);
        s.enviarInvitacion("ana@ejemplo.com", "Luis", "Agencia", SITIO + "/x", 7);

        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("si el SMTP falla no se cae nada: se apunta y ya")
    void smtpFalla() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("SMTP caído")).when(sender).send(any(MimeMessage.class));

        servicio(sender, "re_llave", SITIO).enviarBienvenida("ana@ejemplo.com", "Ana", true);
        // Llegó hasta aquí sin excepción: registrarse no puede fallar porque el correo falle.
    }

    // ------------------------------------------------------------------ vista previa

    @Test
    @DisplayName("deja los correos en target/correos-preview para revisarlos a ojo en un navegador")
    void vistaPrevia() throws IOException {
        Path carpeta = Path.of("target", "correos-preview");
        Files.createDirectories(carpeta);
        Files.writeString(carpeta.resolve("bienvenida-google.html"), bienvenida(true).html(), StandardCharsets.UTF_8);
        Files.writeString(carpeta.resolve("bienvenida-formulario.html"), bienvenida(false).html(), StandardCharsets.UTF_8);
        Files.writeString(carpeta.resolve("recuperacion.html"), recuperacion().html(), StandardCharsets.UTF_8);
        Files.writeString(carpeta.resolve("invitacion.html"), invitacion().html(), StandardCharsets.UTF_8);
        assertThat(Files.exists(carpeta.resolve("bienvenida-google.html"))).isTrue();
    }
}
