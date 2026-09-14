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
}
