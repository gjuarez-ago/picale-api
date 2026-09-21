package com.metricol.api.service.auth;

import static com.metricol.api.service.auth.PlantillaDeCorreo.escapar;
import static com.metricol.api.service.auth.PlantillaDeCorreo.fuerte;
import static com.metricol.api.service.auth.PlantillaDeCorreo.nota;
import static com.metricol.api.service.auth.PlantillaDeCorreo.parrafo;

import java.io.UnsupportedEncodingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.metricol.api.service.auth.PlantillaDeCorreo.Boton;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Los correos que manda la plataforma: bienvenida, código para restablecer la contraseña e
 * invitación a una organización. Todos comparten el marco de {@link PlantillaDeCorreo}.
 *
 * <p>Sale por SMTP de Resend, con la llave en {@code MAIL_PASSWORD}. Sin llave no hay a dónde
 * mandarlo, y en ese caso lo que importa para seguir probando en desarrollo (el código, el
 * enlace) se apunta en el log. Si la llave está y el envío falla, se apunta el error <b>sin el
 * código</b>: eso ya es un problema de infraestructura, no una ayuda para probar.
 *
 * <p><b>Ningún correo lleva una contraseña, jamás.</b> Ni la de quien se registra con un
 * formulario (la conoce él) ni la de quien entra con Google (no tiene ninguna: la cuenta nace con
 * una aleatoria que nadie conoce). Mandar una contraseña por correo es dejarla en un buzón, en
 * un servidor de tránsito y en el historial de quien lo lea.
 *
 * <p>{@code @Async} por dos razones. La primera es la espera: hablar con el SMTP tarda un
 * segundo o dos y la persona no tiene por qué mirarlos. La segunda es más fina: «olvidé mi
 * contraseña» contesta lo mismo exista el correo o no, pero si el envío fuera síncrono, la
 * respuesta tardaría un segundo más justo cuando el correo existe — y eso también delata.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String remitente;
    private final boolean configurado;
    private final String sitio;
    private final String soporte;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String remitente,
            @Value("${spring.mail.password:}") String password,
            @Value("${app.web-url:https://picale.rodtech.cloud}") String sitio,
            @Value("${app.mail.support:contacto@rodtech.cloud}") String soporte) {
        this.mailSender = mailSender;
        this.remitente = remitente;
        this.configurado = password != null && !password.isBlank();
        this.sitio = sitio.replaceAll("/+$", "");
        this.soporte = soporte;
    }

    /** El correo ya armado: lo que se manda y, por separado, para poder revisarlo sin enviar nada. */
    record Correo(String asunto, String html, String texto) {
    }

    // ------------------------------------------------------------------ bienvenida

    /**
     * Le da la bienvenida a quien acaba de crear su cuenta, con lo que sigue.
     *
     * @param conGoogle {@code true} si entró con Google: no tiene contraseña y se le dice cómo
     *                  ponerse una si algún día la quiere
     */
    @Async
    public void enviarBienvenida(String para, String nombre, boolean conGoogle) {
        if (!configurado) {
            log.info("BIENVENIDA para {} (correo sin configurar, no se envio)", para);
            return;
        }
        enviar(para, bienvenida(nombre, para, conGoogle, sitio, soporte), "la bienvenida");
    }

    static Correo bienvenida(String nombre, String correo, boolean conGoogle, String sitio, String soporte) {
        String saludo = saludo(nombre);
        String saludoPlano = saludoPlano(nombre);
        String cuenta = conGoogle
                ? "Entraste con tu cuenta de Google (" + fuerte(escapar(correo)) + "), así que <b>no necesitas contraseña</b>. "
                        + "Si algún día quieres entrar también con correo y contraseña, en la pantalla de acceso toca «Olvidé mi contraseña»."
                : "Tu usuario es " + fuerte(escapar(correo)) + ".";
        String cuentaPlano = conGoogle
                ? "Entraste con tu cuenta de Google (" + correo + "), así que no necesitas contraseña. Si algún día quieres entrar también "
                        + "con correo y contraseña, en la pantalla de acceso toca «Olvidé mi contraseña»."
                : "Tu usuario es " + correo + ".";

        String[][] pasos = {
                { "Cuéntanos de tu negocio", "En «Marca» explicas qué vendes y cómo hablas, para que la IA escriba como tú y no como cualquiera." },
                { "Conecta tus redes", "Facebook, Instagram, TikTok, YouTube y LinkedIn, todas desde un solo lugar." },
                { "Crea tu primera publicación", "Dicta o escribe tu idea y Pícale redacta el texto que le queda a cada red." }
        };
        Boton boton = new Boton("Ir a mi panel", sitio + "/panel");
        String aviso = "Recibes este correo porque se creó una cuenta en Pícale con " + correo
                + ". Si no fuiste tú, escríbenos y la cerramos. Por seguridad, nunca te pediremos tu contraseña por correo.";
        String titulo = "Te damos la bienvenida a Pícale";

        String cuerpo = parrafo(saludo + ", tu cuenta ya está lista. Pícale te ayuda a crear, programar y publicar en todas tus redes "
                + "sin complicarte.")
                + parrafo(fuerte("Para empezar:"))
                + PlantillaDeCorreo.pasos(pasos)
                + parrafo(cuenta);
        String plano = saludoPlano + ", tu cuenta ya está lista. Pícale te ayuda a crear, programar y publicar en todas tus redes sin complicarte.\n\n"
                + "Para empezar:\n1. Cuéntanos de tu negocio: " + pasos[0][1] + "\n2. Conecta tus redes: " + pasos[1][1]
                + "\n3. Crea tu primera publicación: " + pasos[2][1] + "\n\n" + cuentaPlano;

        return new Correo(
                nombre == null || nombre.isBlank() ? titulo : enUnaLinea(nombre) + ", te damos la bienvenida a Pícale",
                PlantillaDeCorreo.pagina(sitio, "Tu cuenta ya está lista. Así empiezas.", titulo, cuerpo, boton, null, aviso, soporte),
                PlantillaDeCorreo.texto(titulo, plano, boton, null, aviso, soporte, sitio));
    }

    // ------------------------------------------------------------------ recuperación de contraseña

    @Async
    public void enviarCodigoDeRecuperacion(String para, String nombre, String codigo, int minutos) {
        if (!configurado) {
            log.info("RECUPERACION para {}: codigo {} (correo sin configurar, no se envio)", para, codigo);
            return;
        }
        enviar(para, recuperacion(codigo, nombre, para, minutos, sitio, soporte), "el codigo de recuperacion");
    }

    static Correo recuperacion(String codigo, String nombre, String correo, int minutos, String sitio, String soporte) {
        String titulo = "Restablece tu contraseña";
        String saludo = saludo(nombre);
        String saludoPlano = saludoPlano(nombre);
        String aviso = "Si no fuiste tú, ignora este correo: tu contraseña sigue igual y nadie puede cambiarla sin este código. "
                + "Y nunca compartas el código con nadie, ni con nosotros.";
        String vence = "El código vence en " + minutos + " minutos. Después tendrás que pedir otro.";

        String cuerpo = parrafo(saludo + ", recibimos una solicitud para cambiar la contraseña de " + fuerte(escapar(correo))
                + ". Escribe este código en la app:")
                + PlantillaDeCorreo.codigo(codigo)
                + nota(escapar(vence));
        String plano = saludoPlano + ", recibimos una solicitud para cambiar la contraseña de " + correo
                + ". Escribe este código en la app:\n\n    " + codigo + "\n\n" + vence;

        return new Correo(
                codigo + " es tu código para restablecer tu contraseña",
                PlantillaDeCorreo.pagina(sitio, codigo + " es tu código para restablecer tu contraseña", titulo, cuerpo, null, null,
                        aviso, soporte),
                PlantillaDeCorreo.texto(titulo, plano, null, null, aviso, soporte, sitio));
    }

    // ------------------------------------------------------------------ invitación

    /**
     * El correo que invita a entrar a una organización.
     *
     * <p>Dice quién invita y a qué: quien lo recibe tiene que reconocer el nombre de su jefe o de la
     * agencia antes de tocar un enlace, o con razón lo tomará por un intento de estafa.
     */
    @Async
    public void enviarInvitacion(String para, String quienInvita, String organizacion, String enlace, int dias) {
        if (!configurado) {
            log.info("INVITACION para {} a {}: {} (correo sin configurar, no se envio)", para, organizacion, enlace);
            return;
        }
        enviar(para, invitacion(quienInvita, organizacion, enlace, dias, sitio, soporte), "la invitacion");
    }

    static Correo invitacion(String quienInvita, String organizacion, String enlace, int dias, String sitio, String soporte) {
        String invita = quienInvita == null || quienInvita.isBlank() ? "Alguien" : enUnaLinea(quienInvita);
        organizacion = enUnaLinea(organizacion);
        String titulo = "Te invitaron a " + organizacion;
        String vence = "El enlace vence en " + dias + " días.";
        Boton boton = new Boton("Aceptar la invitación", enlace);
        String aviso = "Si no esperabas esto, ignora el correo: sin abrir el enlace no pasa nada.";

        String cuerpo = parrafo(fuerte(escapar(invita)) + " te invitó a trabajar en " + fuerte(escapar(organizacion))
                + " con Pícale: crear publicaciones y sacarlas en las redes de sus clientes.");
        String plano = invita + " te invitó a trabajar en " + organizacion
                + " con Pícale: crear publicaciones y sacarlas en las redes de sus clientes.";

        return new Correo(
                invita + " te invitó a " + organizacion + " en Pícale",
                PlantillaDeCorreo.pagina(sitio, invita + " te invitó a " + organizacion + " en Pícale", titulo, cuerpo, boton, vence,
                        aviso, soporte),
                PlantillaDeCorreo.texto(titulo, plano, boton, vence, aviso, soporte, sitio));
    }

    // ------------------------------------------------------------------ envío

    private void enviar(String para, Correo correo, String queEs) {
        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, true, "UTF-8");
            helper.setFrom(remitente, "Pícale");
            helper.setTo(para);
            helper.setSubject(correo.asunto());
            // Las dos versiones: quien no ve HTML (o lo bloquea) lee la de texto, y la entrega mejora.
            helper.setText(correo.texto(), correo.html());
            mailSender.send(mensaje);
        } catch (MessagingException | MailException | UnsupportedEncodingException ex) {
            log.error("No se pudo enviar {} a {}: {}", queEs, para, ex.getMessage());
        }
    }

    private static String saludo(String nombre) {
        return nombre == null || nombre.isBlank() ? "Hola" : "Hola " + escapar(enUnaLinea(nombre));
    }

    private static String saludoPlano(String nombre) {
        return nombre == null || nombre.isBlank() ? "Hola" : "Hola " + enUnaLinea(nombre);
    }

    /**
     * Un texto de una sola línea. Lo que viene de una persona (su nombre, el de su organización) va
     * al asunto del correo, y un salto de línea ahí es la forma clásica de colar otra cabecera.
     */
    static String enUnaLinea(String texto) {
        return texto == null ? "" : texto.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").strip();
    }
}
