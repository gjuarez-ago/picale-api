package com.metricol.api.service.auth;

import java.io.UnsupportedEncodingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Los correos que manda la plataforma. Hoy uno: el código para restablecer la
 * contraseña.
 *
 * <p>Sale por SMTP de Resend, igual que en vivento366, con la llave en
 * {@code MAIL_PASSWORD}. Sin llave no hay a dónde mandarlo, y en ese caso el
 * código se apunta en el log para poder seguir probando en desarrollo. Si la
 * llave está y el envío falla, se apunta el error <b>sin el código</b>: eso ya
 * es un problema de infraestructura, no una ayuda para probar.
 *
 * <p>{@code @Async} por dos razones. La primera es la espera: hablar con el
 * SMTP tarda un segundo o dos y la persona no tiene por qué mirarlos. La
 * segunda es más fina: «olvidé mi contraseña» contesta lo mismo exista el
 * correo o no, pero si el envío fuera síncrono, la respuesta tardaría un
 * segundo más justo cuando el correo existe — y eso también delata.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String remitente;
    private final boolean configurado;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String remitente,
            @Value("${spring.mail.password:}") String password) {
        this.mailSender = mailSender;
        this.remitente = remitente;
        this.configurado = password != null && !password.isBlank();
    }

    @Async
    public void enviarCodigoDeRecuperacion(String para, String nombre, String codigo, int minutos) {
        if (!configurado) {
            log.info("RECUPERACION para {}: codigo {} (correo sin configurar, no se envio)", para, codigo);
            return;
        }

        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, "UTF-8");
            helper.setFrom(remitente, "Pícale");
            helper.setTo(para);
            helper.setSubject(codigo + " es tu código para restablecer tu contraseña");
            helper.setText(cuerpoDeRecuperacion(codigo, nombre, para, minutos), true);
            mailSender.send(mensaje);
        } catch (MessagingException | MailException | UnsupportedEncodingException ex) {
            log.error("No se pudo enviar el correo de recuperacion a {}: {}", para, ex.getMessage());
        }
    }

    /**
     * El correo, escrito a mano y con estilos en línea. Sin motor de plantillas:
     * es un solo correo, y traer Thymeleaf para él sería más configuración que
     * contenido. El día que haya cinco, se saca a plantillas.
     */
    static String cuerpoDeRecuperacion(String codigo, String nombre, String correo, int minutos) {
        String saludo = nombre == null || nombre.isBlank() ? "Hola" : "Hola " + escapar(nombre.strip());
        return """
                <!doctype html>
                <html lang="es">
                <body style="margin:0;padding:0;background:#F3F4F6;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#111827;">
                <div style="display:none;max-height:0;overflow:hidden;">%1$s es tu código para restablecer tu contraseña</div>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0">
                  <tr><td align="center" style="padding:32px 16px;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="max-width:480px;background:#FFFFFF;border-radius:16px;border:1px solid #E5E7EB;">
                      <tr><td style="padding:28px 28px 6px;">
                        <div style="width:44px;height:44px;border-radius:12px;background:#246BFD;background:linear-gradient(135deg,#28C9FD 0%%,#129CFD 35%%,#246BFD 65%%,#0035D7 100%%);color:#FFFFFF;font-weight:700;font-size:20px;line-height:44px;text-align:center;">P</div>
                      </td></tr>
                      <tr><td style="padding:14px 28px 0;font-size:20px;font-weight:700;line-height:28px;">Restablece tu contraseña</td></tr>
                      <tr><td style="padding:10px 28px 0;font-size:15px;line-height:24px;color:#4B5563;">
                        %2$s, recibimos una solicitud para cambiar la contraseña de
                        <strong style="color:#111827;">%3$s</strong>. Escribe este código en la app:
                      </td></tr>
                      <tr><td align="center" style="padding:24px 28px 8px;">
                        <div style="display:inline-block;padding:18px 30px;border-radius:12px;background:#E6F0FE;border:1px solid #BFD7FE;font-size:34px;line-height:40px;font-weight:700;letter-spacing:10px;color:#0035D7;">%1$s</div>
                      </td></tr>
                      <tr><td align="center" style="padding:4px 28px 0;font-size:13px;line-height:20px;color:#6B7280;">
                        El código vence en %4$d minutos. Después tendrás que pedir otro.
                      </td></tr>
                      <tr><td style="padding:24px 28px 0;"><div style="border-top:1px solid #E5E7EB;"></div></td></tr>
                      <tr><td style="padding:18px 28px 28px;font-size:13px;line-height:20px;color:#6B7280;">
                        Si no fuiste tú, ignora este correo: tu contraseña sigue igual y nadie puede cambiarla sin este código.
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body>
                </html>
                """.formatted(codigo, saludo, escapar(correo), minutos);
    }

    /** Lo mínimo para que un nombre con «&lt;» no rompa el HTML ni inyecte nada. */
    private static String escapar(String texto) {
        return texto
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
