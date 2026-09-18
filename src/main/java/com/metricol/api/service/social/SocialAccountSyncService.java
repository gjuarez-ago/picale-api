package com.metricol.api.service.social;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.SocialAccount;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.repository.SocialAccountRepository;

/**
 * Refleja en las {@code social_accounts} de metricol lo que upload-post.com
 * dice que está conectado.
 *
 * <p>Esto no viene de vivento366.api: allá no hace falta, porque publica
 * contra el perfil de upload-post directamente y no lleva tabla propia de
 * cuentas. Aquí sí hace falta, y es el pegamento que faltaba: la app lista las
 * cuentas desde {@code GET /api/v1/social-accounts}, y {@code PostTarget}
 * apunta a una {@link SocialAccount}. Sin este volcado, alguien podría
 * conectar Instagram de verdad y la app seguiría sin tener a qué publicarle.
 *
 * <p>Se conserva la fila cuando una red se desconecta, marcándola
 * {@link SocialAccountStatus#DISCONNECTED} en vez de borrarla: las
 * publicaciones ya hechas apuntan a ella y borrarla dejaría el historial
 * huérfano.
 */
@Service
public class SocialAccountSyncService {

    private static final Logger log = LoggerFactory.getLogger(SocialAccountSyncService.class);

    private final SocialAccountRepository repository;

    public SocialAccountSyncService(SocialAccountRepository repository) {
        this.repository = repository;
    }

    /**
     * @param socialAccounts el mapa {@code social_accounts} tal como lo
     *                       devuelve upload-post: una llave por red, con
     *                       {@code null} si no está conectada. Un mapa vacío
     *                       significa "ninguna conectada" y desconecta todo.
     * @return las redes que la persona apagó desde Pícale. Quien llama tiene
     *         que quitarlas de lo que conteste, o la pantalla las seguirá
     *         enseñando conectadas: upload-post no sabe nada de ese apagado
     *         —el token sigue vivo en su lado— y aquí solo se respeta hacia
     *         la base. Era lo que hacía que el botón "Desconectar" no
     *         pareciera hacer nada.
     */
    @Transactional
    public Set<Platform> sync(Map<String, Object> socialAccounts) {
        List<SocialAccount> existentes = repository.findAllByOrderByConnectedAtDesc();
        Set<Platform> apagadas = EnumSet.noneOf(Platform.class);

        for (Platform platform : Platform.values()) {
            Object cuenta = buscarCuenta(socialAccounts, platform);
            // Se exige un objeto, no solo que la llave venga.
            //
            // upload-post devuelve tres cosas distintas: `null` si la red no
            // se conectó, un objeto con los datos de la cuenta si está lista,
            // y la cadena vacía en un tercer caso — autorizada pero sin nada
            // usable detrás. Eso último pasa en Facebook cuando se concede el
            // acceso sin marcar ninguna Página: el token existe, pero
            // `/facebook/pages` viene vacío y no hay dónde publicar.
            //
            // Se probó a tratar la cadena vacía como conectada y es peor: la
            // red aparecería lista y el fallo llegaría al publicar, después
            // de escribir el texto y subir las fotos. Mejor decir que no está
            // lista, que es la verdad.
            boolean conectada = cuenta instanceof Map;

            Optional<SocialAccount> filaExistente = existentes.stream()
                    .filter(a -> a.getPlatform() == platform)
                    .findFirst();

            if (!conectada) {
                // El caso intermedio merece su propio aviso: la llave vino
                // pero sin datos. Sin esto, "autoricé y no aparece" y "no
                // autoricé" se ven igual en el log, y son problemas
                // distintos con soluciones distintas.
                if (cuenta != null) {
                    log.warn("{} quedó autorizada pero sin datos usables ({}): "
                            + "en Facebook y LinkedIn pasa cuando no se marcó ninguna Página "
                            + "en el diálogo de permisos.", platform, cuenta);
                }

                // Solo se toca si ya existía: no se crean filas
                // DISCONNECTED para redes que nadie conectó nunca.
                filaExistente.ifPresent(fila -> {
                    if (fila.getStatus() != SocialAccountStatus.DISCONNECTED) {
                        fila.setStatus(SocialAccountStatus.DISCONNECTED);
                        repository.save(fila);
                        log.info("Red {} marcada como desconectada", platform);
                    }
                });
                continue;
            }

            // La apagó la persona: se respeta aunque el proveedor la siga
            // dando por buena.
            //
            // Es lo que hace que "Desconectar" signifique algo. El token vive
            // en upload-post y no tenemos forma de revocarlo, así que sin esto
            // la fila volvía a ponerse en verde en la siguiente consulta de
            // estado y el botón no servía para nada.
            if (filaExistente.isPresent() && filaExistente.get().apagadaPorLaPersona()) {
                apagadas.add(platform);
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> datos = (Map<String, Object>) cuenta;
            String nombre = nombreDe(datos, platform);

            SocialAccount fila = filaExistente.orElseGet(() -> SocialAccount.builder()
                    .platform(platform)
                    .connectedAt(LocalDateTime.now())
                    .build());

            // connectedAt se refresca solo al pasar de desconectada a
            // conectada: si no, cada consulta de estado movería la fecha y
            // el orden del listado bailaría sin que nada haya cambiado.
            if (fila.getStatus() != SocialAccountStatus.CONNECTED) {
                fila.setConnectedAt(LocalDateTime.now());
            }
            fila.setStatus(SocialAccountStatus.CONNECTED);
            fila.setAccountName(nombre);
            // La foto la trae `copiarPaginaFija` en `page_picture`; se
            // guardaba nada más el nombre y se tiraba. Con solo el logo de la
            // red, dos Páginas de Facebook distintas se ven idénticas.
            fila.setAvatarUrl(fotoDe(datos));
            fila.setPageId(textoDe(datos.get("page_id")));
            repository.save(fila);
        }

        return apagadas;
    }

    /**
     * upload-post nombra las redes en minúsculas, igual que {@code name()} en
     * minúscula de cada valor del enum.
     *
     * <p>Hubo aquí un caso aparte para X, que convivía con {@code twitter}
     * según el endpoint. Se fue con X: esta versión no la ofrece.
     */
    private Object buscarCuenta(Map<String, Object> socialAccounts, Platform platform) {
        return socialAccounts.get(platform.name().toLowerCase());
    }

    /**
     * Se prefiere el nombre de la Página/organización fijada cuando la hay:
     * es donde de verdad va a salir la publicación, y ver el perfil personal
     * en la lista sería engañoso. {@code page_name} lo agrega
     * {@link UploadPostConnectService} al consultar el estado.
     */
    private String nombreDe(Map<String, Object> cuenta, Platform platform) {
        return primero(cuenta, "page_name", "display_name", "username", "name")
                .orElse(platform.name());
    }

    /**
     * La foto de la cuenta, o de la Página fijada cuando la hay.
     *
     * <p>{@code page_picture} va primero por lo mismo que {@code page_name}:
     * es donde de verdad sale la publicación, y enseñar el avatar personal
     * cuando se publica en una Página sería engañoso.
     *
     * <p>Se prueban varios nombres de campo porque cada red devuelve el suyo
     * y el proveedor no lo documenta del todo. Sin foto se devuelve
     * {@code null} y la pantalla cae al logo de la red, que es lo que había
     * antes: una ausencia, no un error.
     */
    private String fotoDe(Map<String, Object> cuenta) {
        String url = primero(cuenta, "page_picture", "profile_picture", "picture",
                "profile_image_url", "avatar_url", "avatar").orElse(null);

        // Una foto que no quepa se descarta, no se recorta ni revienta.
        //
        // Recortarla daría una URL rota, que es peor que ninguna: la pantalla
        // intentaría cargarla y enseñaría un hueco. Y dejarla pasar es lo que
        // ya falló una vez — el insert tiraba la consulta de conexiones
        // entera y no se podían ver las redes por culpa de una foto.
        if (url != null && url.length() > SocialAccount.MAX_AVATAR_URL) {
            log.warn("Foto de cuenta descartada por larga ({} caracteres, tope {})",
                    url.length(), SocialAccount.MAX_AVATAR_URL);
            return null;
        }
        return url;
    }

    /** El primer campo con algo dentro, de los que se le pasen. */
    private java.util.Optional<String> primero(Map<String, Object> cuenta, String... campos) {
        for (String campo : campos) {
            String valor = textoDe(cuenta.get(campo));
            if (valor != null) {
                return java.util.Optional.of(valor);
            }
        }
        return java.util.Optional.empty();
    }

    private String textoDe(Object valor) {
        if (valor == null) {
            return null;
        }
        String texto = valor.toString().trim();
        return texto.isEmpty() ? null : texto;
    }
}
