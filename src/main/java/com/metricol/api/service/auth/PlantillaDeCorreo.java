package com.metricol.api.service.auth;

import java.time.Year;

/**
 * El marco de todos los correos de Pícale: una sola forma de verse.
 *
 * <p>Antes cada correo traía su propio HTML pegado, con una «P» dibujada en vez
 * del logotipo y la fuente del sistema. Aquí vive lo común —encabezado, tipografía,
 * botón, pie— y cada correo solo aporta su contenido, así que un cambio de imagen
 * se hace una vez y los correos nunca se ven distintos entre sí.
 *
 * <h3>La tipografía</h3>
 * La marca usa Manrope. Un correo NO puede garantizarla: se carga desde Google Fonts
 * y solo la respetan los clientes que aceptan fuentes web (Apple Mail, iOS Mail, Outlook para
 * Mac, la app de Samsung). Gmail, Outlook de escritorio y la mayoría de los clientes de
 * Android la ignoran, y caen en la siguiente de la lista, que se eligió parecida
 * (una sans-serif moderna del sistema). Es el límite del medio, no de la plantilla.
 *
 * <h3>Lo demás</h3>
 * <ul>
 *   <li>Solo tablas y estilos en línea: es lo que los clientes de correo entienden.</li>
 *   <li>Modo oscuro para los clientes que lo respetan.</li>
 *   <li>Cada correo lleva también su versión en texto plano ({@link #texto}): mejora la entrega y
 *       sirve a quien lee sin imágenes.</li>
 *   <li>Todo lo que viene de una persona se escapa: un nombre con «&lt;» no rompe nada.</li>
 * </ul>
 */
final class PlantillaDeCorreo {

    /** Manrope primero; si el cliente no la carga, una del sistema parecida. */
    static final String FUENTE = "'Manrope',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif";

    private static final String GOOGLE_FONTS =
            "https://fonts.googleapis.com/css2?family=Manrope:wght@400;600;700;800&display=swap";

    private static final String NAVY = "#102A56";
    private static final String AZUL = "#246BFD";
    private static final String TEXTO = "#4B5563";
    private static final String SUAVE = "#6B7280";

    private PlantillaDeCorreo() {
    }

    /** Un botón de llamada a la acción. */
    record Boton(String texto, String url) {
    }

    // ------------------------------------------------------------------ piezas de contenido

    /** Un párrafo del cuerpo. {@code html} ya viene escapado por quien lo arma. */
    static String parrafo(String html) {
        return "<p class=\"texto\" style=\"margin:0 0 16px;font-size:16px;line-height:26px;color:" + TEXTO + ";\">"
                + html + "</p>";
    }

    /** Texto destacado dentro de un párrafo. */
    static String fuerte(String textoYaEscapado) {
        return "<strong class=\"titulo\" style=\"color:#111827;\">" + textoYaEscapado + "</strong>";
    }

    /** Una nota pequeña y centrada, como «el código vence en 15 minutos». */
    static String nota(String html) {
        return "<p class=\"suave\" style=\"margin:0 0 4px;font-size:13px;line-height:20px;text-align:center;color:" + SUAVE
                + ";\">" + html + "</p>";
    }

    /** El código de seis dígitos, grande y espaciado para leerlo de un vistazo. */
    static String codigo(String codigo) {
        return "<div style=\"text-align:center;margin:8px 0 14px;\"><span class=\"codigo\" style=\"display:inline-block;padding:16px 28px;"
                + "border-radius:14px;background:#E6F0FE;border:1px solid #BFD7FE;font-size:34px;line-height:40px;font-weight:800;"
                + "letter-spacing:10px;color:#0035D7;\">" + escapar(codigo) + "</span></div>";
    }

    /** Una lista numerada de pasos, cada uno con un título y una línea que lo explica. */
    static String pasos(String[][] titulosYTextos) {
        StringBuilder sb = new StringBuilder(
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:4px 0 8px;\">");
        for (int i = 0; i < titulosYTextos.length; i++) {
            sb.append("<tr><td valign=\"top\" width=\"40\" style=\"padding:6px 0;\">")
                    .append("<div style=\"width:28px;height:28px;border-radius:14px;background:#E6F0FE;color:#0035D7;font-size:14px;")
                    .append("font-weight:800;line-height:28px;text-align:center;\">").append(i + 1).append("</div></td>")
                    .append("<td valign=\"top\" style=\"padding:6px 0 10px;\">")
                    .append("<div class=\"titulo\" style=\"font-size:15px;line-height:22px;font-weight:700;color:#111827;\">")
                    .append(escapar(titulosYTextos[i][0])).append("</div>")
                    .append("<div class=\"texto\" style=\"font-size:14px;line-height:22px;color:").append(TEXTO).append(";\">")
                    .append(escapar(titulosYTextos[i][1])).append("</div></td></tr>");
        }
        return sb.append("</table>").toString();
    }

    // ------------------------------------------------------------------ la página

    /**
     * El correo completo.
     *
     * @param sitio          la dirección pública del sitio, sin diagonal final: de ahí salen el logotipo y los enlaces del pie
     * @param preencabezado  lo que muestra el buzón junto al asunto antes de abrirlo
     * @param titulo         el título grande
     * @param cuerpoHtml     el contenido, armado con las piezas de arriba
     * @param boton          el botón principal, o {@code null}
     * @param notaDelBoton   una línea bajo el botón (vencimiento, por ejemplo), o {@code null}
     * @param aviso          por qué llega este correo y qué hacer si no se espera
     * @param soporte        el correo de ayuda
     */
    static String pagina(String sitio, String preencabezado, String titulo, String cuerpoHtml, Boton boton,
            String notaDelBoton, String aviso, String soporte) {
        StringBuilder h = new StringBuilder(4096);
        h.append("<!doctype html>\n<html lang=\"es\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
                .append("<meta name=\"color-scheme\" content=\"light dark\">\n<meta name=\"supported-color-schemes\" content=\"light dark\">\n")
                .append("<title>").append(escapar(titulo)).append("</title>\n")
                .append("<link href=\"").append(GOOGLE_FONTS).append("\" rel=\"stylesheet\">\n")
                .append("<style>\n@import url('").append(GOOGLE_FONTS).append("');\n")
                .append("@media (max-width:560px){.tarjeta{border-radius:0 !important;border-left:0 !important;border-right:0 !important}")
                .append(".relleno{padding-left:22px !important;padding-right:22px !important}.exterior{padding:0 !important}}\n")
                .append("@media (prefers-color-scheme:dark){.fondo{background:#0B1220 !important}.tarjeta{background:#111A2E !important;border-color:#22304F !important}")
                .append(".titulo{color:#FFFFFF !important}.texto{color:#B6C2D9 !important}.suave{color:#8A97B2 !important}")
                .append(".linea{border-color:#22304F !important}.codigo{background:#0F2A5C !important;border-color:#1E4AA8 !important;color:#9CC3FF !important}")
                .append(".logo{background:#FFFFFF !important;padding:8px 12px !important;border-radius:10px !important}}\n")
                .append("</style>\n</head>\n");

        h.append("<body class=\"fondo\" style=\"margin:0;padding:0;background:#F3F6FB;font-family:").append(FUENTE)
                .append(";color:#111827;-webkit-text-size-adjust:100%;\">\n")
                // El preencabezado, oculto, con relleno para que el buzón no muestre el inicio del cuerpo detrás.
                .append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;\">")
                .append(escapar(preencabezado)).append("&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;</div>\n")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" class=\"fondo\" style=\"background:#F3F6FB;\">\n")
                .append("<tr><td align=\"center\" class=\"exterior\" style=\"padding:32px 16px;\">\n")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" class=\"tarjeta\" ")
                .append("style=\"max-width:560px;background:#FFFFFF;border-radius:20px;border:1px solid #E5E7EB;overflow:hidden;\">\n");

        // La franja de la marca.
        h.append("<tr><td style=\"height:5px;line-height:5px;font-size:0;background:").append(AZUL)
                .append(";background-image:linear-gradient(90deg,#28C9FD 0%,#129CFD 35%,#246BFD 65%,#0035D7 100%);\">&nbsp;</td></tr>\n");

        // El logotipo.
        h.append("<tr><td class=\"relleno\" style=\"padding:28px 36px 0;\">")
                .append("<a href=\"").append(escapar(sitio)).append("\" style=\"text-decoration:none;\">")
                .append("<img class=\"logo\" src=\"").append(escapar(sitio)).append("/logotipo-navbar.png\" width=\"132\" height=\"45\" alt=\"Pícale\" ")
                .append("style=\"display:block;border:0;outline:none;width:132px;height:auto;font-family:").append(FUENTE)
                .append(";font-size:22px;font-weight:800;color:").append(NAVY).append(";\"></a></td></tr>\n");

        // El título y el cuerpo.
        h.append("<tr><td class=\"relleno titulo\" style=\"padding:22px 36px 0;font-size:26px;line-height:34px;font-weight:800;letter-spacing:-0.3px;color:")
                .append(NAVY).append(";\">").append(escapar(titulo)).append("</td></tr>\n")
                .append("<tr><td class=\"relleno\" style=\"padding:14px 36px 0;\">").append(cuerpoHtml).append("</td></tr>\n");

        // El botón.
        if (boton != null) {
            h.append("<tr><td align=\"center\" class=\"relleno\" style=\"padding:10px 36px 6px;\">")
                    .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
                    .append("<td align=\"center\" bgcolor=\"").append(AZUL).append("\" style=\"border-radius:12px;background:").append(AZUL).append(";\">")
                    .append("<a href=\"").append(escapar(boton.url())).append("\" style=\"display:inline-block;padding:15px 32px;font-family:").append(FUENTE)
                    .append(";font-size:16px;line-height:20px;font-weight:700;color:#FFFFFF;text-decoration:none;border-radius:12px;\">")
                    .append(escapar(boton.texto())).append("</a></td></tr></table></td></tr>\n");
            if (notaDelBoton != null && !notaDelBoton.isBlank()) {
                h.append("<tr><td class=\"relleno\" style=\"padding:8px 36px 0;\">").append(nota(escapar(notaDelBoton))).append("</td></tr>\n");
            }
        }

        // La razón del correo y el pie.
        h.append("<tr><td class=\"relleno\" style=\"padding:28px 36px 0;\"><div class=\"linea\" style=\"border-top:1px solid #E5E7EB;\"></div></td></tr>\n")
                .append("<tr><td class=\"relleno suave\" style=\"padding:18px 36px 0;font-size:13px;line-height:21px;color:").append(SUAVE).append(";\">")
                .append(escapar(aviso)).append("</td></tr>\n")
                .append("<tr><td class=\"relleno suave\" style=\"padding:20px 36px 30px;font-size:12px;line-height:19px;color:").append(SUAVE).append(";\">")
                .append("<strong class=\"titulo\" style=\"color:").append(NAVY).append(";\">Pícale</strong> · Tus redes, más fácil<br>")
                .append("¿Necesitas ayuda? Escríbenos a <a href=\"mailto:").append(escapar(soporte)).append("\" style=\"color:").append(AZUL)
                .append(";text-decoration:none;\">").append(escapar(soporte)).append("</a><br>")
                .append("<a href=\"").append(escapar(sitio)).append("/privacidad\" style=\"color:").append(SUAVE).append(";\">Aviso de privacidad</a>")
                .append(" &nbsp;·&nbsp; <a href=\"").append(escapar(sitio)).append("/terminos\" style=\"color:").append(SUAVE).append(";\">Términos y condiciones</a><br>")
                .append("© ").append(Year.now().getValue()).append(" Rodtech Solutions, S.A. de C.V.</td></tr>\n")
                .append("</table>\n</td></tr>\n</table>\n</body>\n</html>\n");
        return h.toString();
    }

    /** La versión en texto plano: el mismo mensaje, sin diseño. */
    static String texto(String titulo, String cuerpoPlano, Boton boton, String notaDelBoton, String aviso, String soporte,
            String sitio) {
        StringBuilder t = new StringBuilder();
        t.append(titulo).append("\n\n").append(cuerpoPlano.strip()).append("\n");
        if (boton != null) {
            t.append("\n").append(boton.texto()).append(": ").append(boton.url()).append("\n");
            if (notaDelBoton != null && !notaDelBoton.isBlank()) {
                t.append(notaDelBoton).append("\n");
            }
        }
        t.append("\n").append(aviso).append("\n\n--\nPícale · Tus redes, más fácil\n")
                .append("¿Necesitas ayuda? ").append(soporte).append("\n")
                .append(sitio).append("/privacidad · ").append(sitio).append("/terminos\n");
        return t.toString();
    }

    /** Lo mínimo para que un nombre con «&lt;» no rompa el HTML ni inyecte nada. */
    static String escapar(String texto) {
        if (texto == null) {
            return "";
        }
        return texto
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
