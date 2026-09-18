package com.metricol.api.service.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.metricol.api.entity.SocialAccount;
import com.metricol.api.enums.Platform;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.repository.SocialAccountRepository;

/**
 * Qué se hace con lo que upload-post dice que está conectado.
 *
 * <p>Lo que se prueba aquí sobre todo es el apagado manual. Desconectar una
 * red desde Pícale no revoca nada en el proveedor —el token vive en su lado y
 * no hay forma de retirarlo desde aquí—, así que upload-post la sigue dando
 * por conectada en cada consulta. La marca de "la apagó la persona" es lo
 * único que sostiene esa decisión, y si no sale de aquí hacia la respuesta, la
 * pantalla vuelve a pintar la red en verde en el mismo segundo y el botón de
 * desconectar no parece hacer nada. Es justo lo que pasaba.
 */
class SocialAccountSyncServiceTest {

    private final SocialAccountRepository repository = mock(SocialAccountRepository.class);
    private final SocialAccountSyncService sync = new SocialAccountSyncService(repository);

    private SocialAccount fila(Platform red, SocialAccountStatus estado, boolean apagada) {
        return SocialAccount.builder()
                .platform(red)
                .accountName(red.getLabel())
                .status(estado)
                .desactivadaPorUsuario(apagada)
                .build();
    }

    /** Lo que upload-post manda cuando una red está lista. */
    private Map<String, Object> cuenta(String usuario) {
        return Map.of("username", usuario, "display_name", usuario);
    }

    @Test
    void laRedApagadaPorLaPersonaSeDevuelveParaQuitarlaDeLaRespuesta() {
        when(repository.findAllByOrderByConnectedAtDesc()).thenReturn(
                List.of(fila(Platform.INSTAGRAM, SocialAccountStatus.DISCONNECTED, true)));

        // upload-post la sigue dando por conectada, porque para él lo está.
        var apagadas = sync.sync(Map.of("instagram", cuenta("picale")));

        assertThat(apagadas).containsExactly(Platform.INSTAGRAM);
    }

    @Test
    void laRedApagadaNoVuelveAPonerseEnConectada() {
        SocialAccount instagram = fila(Platform.INSTAGRAM, SocialAccountStatus.DISCONNECTED, true);
        when(repository.findAllByOrderByConnectedAtDesc()).thenReturn(List.of(instagram));

        sync.sync(Map.of("instagram", cuenta("picale")));

        assertThat(instagram.getStatus()).isEqualTo(SocialAccountStatus.DISCONNECTED);
    }

    @Test
    void unaRedNormalNiSeDevuelveNiSeToca() {
        SocialAccount tiktok = fila(Platform.TIKTOK, SocialAccountStatus.DISCONNECTED, false);
        when(repository.findAllByOrderByConnectedAtDesc()).thenReturn(List.of(tiktok));

        var apagadas = sync.sync(Map.of("tiktok", cuenta("pancho_02")));

        assertThat(apagadas).isEmpty();
        assertThat(tiktok.getStatus()).isEqualTo(SocialAccountStatus.CONNECTED);
        assertThat(tiktok.getAccountName()).isEqualTo("pancho_02");
    }

    @Test
    void laQueElProveedorYaNoReportaSeMarcaDesconectadaYNoCuentaComoApagada() {
        // Desconectada desde la propia red, no desde Pícale. Son dos cosas
        // distintas: esta no hay que esconderla de la respuesta, porque el
        // proveedor ya no la manda.
        SocialAccount youtube = fila(Platform.YOUTUBE, SocialAccountStatus.CONNECTED, false);
        when(repository.findAllByOrderByConnectedAtDesc()).thenReturn(List.of(youtube));

        var apagadas = sync.sync(Map.of());

        assertThat(apagadas).isEmpty();
        assertThat(youtube.getStatus()).isEqualTo(SocialAccountStatus.DISCONNECTED);
    }

    @Test
    void unaAutorizadaSinDatosUsablesNoCuentaComoConectada() {
        // El caso de Facebook cuando se autoriza sin marcar ninguna Página:
        // llega la llave con cadena vacía en vez de un objeto.
        SocialAccount facebook = fila(Platform.FACEBOOK, SocialAccountStatus.CONNECTED, false);
        when(repository.findAllByOrderByConnectedAtDesc()).thenReturn(List.of(facebook));

        sync.sync(Map.of("facebook", ""));

        assertThat(facebook.getStatus()).isEqualTo(SocialAccountStatus.DISCONNECTED);
    }

    @Test
    void variasApagadasSalenTodas() {
        when(repository.findAllByOrderByConnectedAtDesc()).thenReturn(List.of(
                fila(Platform.INSTAGRAM, SocialAccountStatus.DISCONNECTED, true),
                fila(Platform.TIKTOK, SocialAccountStatus.DISCONNECTED, true),
                fila(Platform.FACEBOOK, SocialAccountStatus.CONNECTED, false)));

        var apagadas = sync.sync(Map.of(
                "instagram", cuenta("picale"),
                "tiktok", cuenta("pancho_02"),
                "facebook", cuenta("Solaris")));

        assertThat(apagadas).containsExactlyInAnyOrder(Platform.INSTAGRAM, Platform.TIKTOK);
    }
}
