package com.metricol.api.models.response;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSummaryResponse {
    private long scheduledCount;
    private long publishedCount;
    private long connectedAccountsCount;
    private List<PostResponse> recentPosts;

    /**
     * Lo de HOY: lo que sale o salió hoy, y lo que espera algo de alguien
     * (falló o está saliendo) aunque sea de otro día. Es lo que enseña la
     * pantalla Hoy; {@code recentPosts} se queda para quien ya lo usa.
     */
    private List<PostResponse> todayPosts;
}
