package com.socialcoisa.rede.jobs.domain;

import java.time.Instant;

public record AppliedJob(Job job, Instant appliedAt, long applicationId) {
}
