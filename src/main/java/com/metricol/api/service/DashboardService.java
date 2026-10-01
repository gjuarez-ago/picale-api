package com.metricol.api.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.models.response.DashboardSummaryResponse;
import com.metricol.api.models.response.PostResponse;
import com.metricol.api.repository.PostRepository;
import com.metricol.api.repository.SocialAccountRepository;

@Service
public class DashboardService {

    private final PostRepository postRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final PostService postService;

    public DashboardService(
            PostRepository postRepository,
            SocialAccountRepository socialAccountRepository,
            PostService postService) {
        this.postRepository = postRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.postService = postService;
    }

    public DashboardSummaryResponse getSummary() {
        // Lo archivado se guarda, pero deja de ser lo que alguien mira al abrir la aplicacion.
        List<PostResponse> vivas = postService.list().stream()
                .filter(post -> post.getArchivedAt() == null)
                .toList();
        return DashboardSummaryResponse.builder()
                // "Pendientes de salir" incluye las que estan en la cola: una
                // publicacion inmediata pasa por QUEUED antes de salir, y
                // contar solo SCHEDULED la habria hecho desaparecer del
                // resumen durante ese rato.
                .scheduledCount(postRepository.countByStatusAndDeletedAtIsNull(PostStatus.SCHEDULED)
                        + postRepository.countByStatusAndDeletedAtIsNull(PostStatus.QUEUED)
                        + postRepository.countByStatusAndDeletedAtIsNull(PostStatus.PUBLISHING))
                .publishedCount(postRepository.countByStatusAndDeletedAtIsNull(PostStatus.PUBLISHED))
                .connectedAccountsCount(socialAccountRepository.countByStatus(SocialAccountStatus.CONNECTED))
                .recentPosts(vivas.stream().limit(5).toList())
                .todayPosts(deHoy(vivas, LocalDate.now()))
                .build();
    }

    /** Cuántas como mucho: la pantalla es un resumen, el resto está en Publicaciones. */
    static final int TOPE_DE_HOY = 20;

    /**
     * Lo de hoy, en el orden del día: lo que espera algo (falló, en cola,
     * saliendo) de cualquier día, y lo programado o publicado hoy. Los
     * borradores no: no salen hoy, salen cuando alguien los programe.
     */
    static List<PostResponse> deHoy(List<PostResponse> todas, LocalDate hoy) {
        return todas.stream()
                .filter(p -> esperaAlgo(p) || esDeHoy(p.getScheduledAt(), hoy) || esDeHoy(p.getPublishedAt(), hoy))
                .filter(p -> p.getStatus() != PostStatus.DRAFT)
                .sorted(Comparator.comparing(DashboardService::cuando, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(TOPE_DE_HOY)
                .toList();
    }

    private static boolean esperaAlgo(PostResponse p) {
        return p.getStatus() == PostStatus.FAILED || p.getStatus() == PostStatus.QUEUED
                || p.getStatus() == PostStatus.PUBLISHING;
    }

    private static boolean esDeHoy(LocalDateTime t, LocalDate hoy) {
        return t != null && t.toLocalDate().equals(hoy);
    }

    private static LocalDateTime cuando(PostResponse p) {
        return p.getPublishedAt() != null ? p.getPublishedAt() : p.getScheduledAt();
    }
}
