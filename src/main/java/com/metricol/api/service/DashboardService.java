package com.metricol.api.service;

import org.springframework.stereotype.Service;

import com.metricol.api.enums.PostStatus;
import com.metricol.api.enums.SocialAccountStatus;
import com.metricol.api.models.response.DashboardSummaryResponse;
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
        return DashboardSummaryResponse.builder()
                // "Pendientes de salir" incluye las que estan en la cola: una
                // publicacion inmediata pasa por QUEUED antes de salir, y
                // contar solo SCHEDULED la habria hecho desaparecer del
                // resumen durante ese rato.
                .scheduledCount(postRepository.countByStatus(PostStatus.SCHEDULED)
                        + postRepository.countByStatus(PostStatus.QUEUED)
                        + postRepository.countByStatus(PostStatus.PUBLISHING))
                .publishedCount(postRepository.countByStatus(PostStatus.PUBLISHED))
                .connectedAccountsCount(socialAccountRepository.countByStatus(SocialAccountStatus.CONNECTED))
                .recentPosts(postService.list().stream()
                        // Lo archivado se guarda, pero deja de ser lo que
                        // alguien mira al abrir la aplicacion.
                        .filter(post -> post.getArchivedAt() == null)
                        .limit(5)
                        .toList())
                .build();
    }
}
