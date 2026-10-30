package com.socialcoisa.rede.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.jobs.domain.JobApplicationStatus;
import com.socialcoisa.rede.jobs.domain.JobStatus;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.ApiException;

@ExtendWith(MockitoExtension.class)
class JobServiceTests {

	private static final String USER = "user_job_tests";

	@Mock
	private JobRepository repository;

	@Mock
	private ReputationService reputationService;

	@Mock
	private com.socialcoisa.rede.profile.application.port.out.UserProfileRepository userProfileRepository;

	@Mock
	private com.socialcoisa.rede.profile.application.port.out.ProfileExtensionRepository extensionRepository;

	@Mock
	private com.socialcoisa.rede.jobs.application.port.out.JobApplicationDelivery delivery;

	/** Notificacao ao CANDIDATO (a `delivery` acima e a do empregador). */
	@Mock
	private com.socialcoisa.rede.jobs.application.port.out.JobApplicationStatusNotifier statusNotifier;

	private JobService service;

	@BeforeEach
	void setUp() {
		var noopPublisher = new org.springframework.context.ApplicationEventPublisher() {
			@Override public void publishEvent(Object event) { }
		};
		service = new JobService(repository, reputationService, userProfileRepository,
			new com.socialcoisa.rede.corporate.application.port.out.CorporateProfileRepository() {
				@Override
				public com.socialcoisa.rede.corporate.domain.CorporateProfile save(
						com.socialcoisa.rede.corporate.domain.CorporateProfile profile) {
					return profile;
				}

				@Override
				public java.util.Optional<com.socialcoisa.rede.corporate.domain.CorporateProfile> findByUserId(
						String userId) {
					return java.util.Optional.empty();
				}

				@Override
				public java.util.Optional<com.socialcoisa.rede.corporate.domain.CorporateProfile> findByUsername(
						String username) {
					return java.util.Optional.empty();
				}

				@Override
				public java.util.List<com.socialcoisa.rede.corporate.domain.CorporateProfile> findByUserIds(
						java.util.Collection<String> userIds) {
					return java.util.List.of();
				}

				@Override
				public boolean existsByUsername(String username) {
					return false;
				}
			},
			extensionRepository, delivery,
			statusNotifier,
			new com.socialcoisa.rede.jobs.application.port.out.JobAbandonPenaltyRepository() {
				@Override
				public boolean record(String jobId, String userId, int unanswered) {
					return true;
				}

				@Override
				public int countByUser(String userId) {
					return 0;
				}
			},
			new tools.jackson.databind.ObjectMapper(), Clock.systemUTC(),
			new com.socialcoisa.rede.shared.security.UserRateLimiter(Clock.systemUTC(), noopPublisher,
				10, 60, 30, 60, 100, 30, 60, 1, 3, 60),
			new com.socialcoisa.rede.shared.security.BehavioralTimingGuard(Clock.systemUTC(), noopPublisher));
	}

	@Test
	void normalizesCurrencySymbolOutOfTheSalaryAndKeepsTheChosenCurrency() {
		when(reputationService.reputationOf(USER)).thenReturn(300);

		Job job = service.create("Pessoa Desenvolvedora", "Acme", null, "Remoto", "CLT", "PLENO",
			"R$ 8.000 – R$ 12.000", "brl", "1500", null, "MONTH", "Vaga de teste com valor.",
			List.of("Java"), List.of(), USER, true);

		assertThat(job.salary()).isEqualTo("8.000 – 12.000");
		assertThat(job.currency()).isEqualTo("BRL");
		assertThat(job.communityAgreedAt()).isNotNull();
	}

	@Test
	void dollarSalaryIsAlsoNormalized() {
		when(reputationService.reputationOf(USER)).thenReturn(300);

		Job job = service.create("Backend Engineer", "Acme", null, "Remote", "PJ", "SENIOR",
			"US$ 90,000/year", "usd", "90000", null, "YEAR", "Vaga de teste com valor.",
			List.of("Go"), List.of(), USER, true);

		assertThat(job.salary()).isEqualTo("90,000/year");
		assertThat(job.currency()).isEqualTo("USD");
	}

	@Test
	void defaultsToBrlWhenCurrencyIsOmitted() {
		when(reputationService.reputationOf(USER)).thenReturn(300);

		Job job = service.create("Suporte", "Acme", null, "São Paulo", "CLT", "JUNIOR", null, null,
			"1500", null, "MONTH", "Vaga de teste com valor.", List.of(), List.of(), USER, true);

		assertThat(job.currency()).isEqualTo("BRL");
		assertThat(job.salary()).isNull();
	}

	@Test
	void refusesToPublishWithoutTheCommunityAgreement() {
		when(reputationService.reputationOf(USER)).thenReturn(300);

		assertThatThrownBy(() -> service.create("Vaga", "Acme", null, null, "CLT", "PLENO", null, "BRL",
			"1500", null, "MONTH", "Vaga de teste com valor.", List.of(), List.of(), USER, false))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("regras da comunidade");
	}

	@Test
	void refusesUnsupportedCurrency() {
		when(reputationService.reputationOf(USER)).thenReturn(300);

		assertThatThrownBy(() -> service.create("Vaga", "Acme", null, null, "CLT", "PLENO", "1000", "BTC",
			"1500", null, "MONTH", "Vaga de teste com valor.", List.of(), List.of(), USER, true))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("Moeda nao suportada");
	}

	@Test
	void keepsTheReputationGate() {
		when(reputationService.reputationOf(USER)).thenReturn(JobService.MIN_REPUTATION_TO_CREATE_JOB);

		assertThatThrownBy(() -> service.create("Vaga", "Acme", null, null, "CLT", "PLENO", null, "BRL",
			"1500", null, "MONTH", "Vaga de teste com valor.", List.of(), List.of(), USER, true))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("reputacao");
	}

	// ---------------- Ciclo de vida da vaga (Fase 3) ----------------

	private Job openJob() {
		java.time.Instant now = java.time.Instant.now();
		return Job.create("job_1", "Backend", "Acme", null, "Remoto", "CLT", "SENIOR", null, "BRL",
			new java.math.BigDecimal("1500"), null, "MONTH", "Vaga de teste com valor.",
			List.of("Java"), List.of(), USER, now, now);
	}

	@Test
	void closesTheJobAndRecordsWhen() {
		Job open = openJob();
		when(repository.findByIdForUpdate("job_1")).thenReturn(java.util.Optional.of(open));

		Job closed = service.close("job_1", USER);

		assertThat(closed.status()).isEqualTo(JobStatus.CLOSED);
		assertThat(closed.closedAt()).isNotNull();
		verify(repository).save(closed);
	}

	@Test
	void refusesCloseFromSomeoneElse() {
		when(repository.findByIdForUpdate("job_1")).thenReturn(java.util.Optional.of(openJob()));

		assertThatThrownBy(() -> service.close("job_1", "user_outro"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("nao criou");

		verify(repository, never()).save(any());
	}

	@Test
	void refusesClosingTwice() {
		Job closed = openJob().closed(java.time.Instant.now());
		when(repository.findByIdForUpdate("job_1")).thenReturn(java.util.Optional.of(closed));

		assertThatThrownBy(() -> service.close("job_1", USER))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("ja esta encerrada");
	}

	@Test
	void editingDoesNotReopenAClosedJob() {
		Job closed = openJob().closed(java.time.Instant.now());
		when(repository.findByIdForUpdate("job_1")).thenReturn(java.util.Optional.of(closed));

		Job updated = service.update("job_1", "Backend II", "Acme", null, "Remoto", "CLT", "SENIOR", null, "BRL",
			"1500", null, "MONTH", "Vaga de teste com valor.", List.of("Java"), List.of(), USER);

		assertThat(updated.status()).isEqualTo(JobStatus.CLOSED);
		assertThat(updated.closedAt()).isEqualTo(closed.closedAt());
	}

	@Test
	void refusesApplicationWhenTheJobIsClosed() {
		Job closed = openJob().closed(java.time.Instant.now());
		when(repository.findByIdForUpdate("job_1")).thenReturn(java.util.Optional.of(closed));

		assertThatThrownBy(() -> service.apply("job_1", "user_candidato", "oi", List.of()))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("encerrada");

		// O ponto da regra: nada e gravado e o empregador nao e notificado.
		verify(repository, never()).saveApplication(any());
		verifyNoInteractions(delivery);
	}

	@Test
	void stillAcceptsApplicationWhileTheJobIsOpen() {
		Job open = openJob();
		when(repository.findByIdForUpdate("job_1")).thenReturn(java.util.Optional.of(open));
		when(repository.hasApplied("job_1", "user_candidato")).thenReturn(false);
		when(repository.saveApplication(any())).thenReturn("app_1");
		when(userProfileRepository.findById("user_candidato")).thenReturn(java.util.Optional.empty());

		JobApplication application = service.apply("job_1", "user_candidato", "oi", List.of());

		assertThat(application.jobId()).isEqualTo("job_1");
		verify(delivery).deliver(any(), any());
	}

	// ---------------- Triagem: ciclo de vida da candidatura (Fase 4) ----------------

	/** Monta a candidatura no estado pedido percorrendo o caminho VALIDO (o dominio recusa atalhos). */
	private JobApplication applicationIn(JobApplicationStatus status) {
		JobApplication applied = JobApplication.create("app_1", "job_1", "user_candidato", "oi", "{}",
			java.time.Instant.now(), List.of());
		if (status == JobApplicationStatus.APPLIED) {
			return applied;
		}
		JobApplication inReview = applied.withStatus(JobApplicationStatus.IN_REVIEW, null, java.time.Instant.now());
		return switch (status) {
			case IN_REVIEW -> inReview;
			case ADVANCED -> inReview.withStatus(JobApplicationStatus.ADVANCED, null, java.time.Instant.now());
			// Reprovar exige motivo no dominio: o teste passa um, como o empregador faria.
			case NOT_SELECTED -> inReview.withStatus(JobApplicationStatus.NOT_SELECTED,
				"Seguimos com outra pessoa.", java.time.Instant.now());
			case APPLIED -> applied;
		};
	}

	private void givenApplication(JobApplication application) {
		when(repository.findByIdForUpdate("job_1")).thenReturn(java.util.Optional.of(openJob()));
		when(repository.findApplicationById("app_1")).thenReturn(java.util.Optional.of(application));
	}

	@Test
	void newApplicationStartsAsApplied() {
		assertThat(applicationIn(JobApplicationStatus.APPLIED).status())
			.isEqualTo(JobApplicationStatus.APPLIED);
	}

	@Test
	void ownerMovesTheApplicationAndTheCandidateIsNotified() {
		givenApplication(applicationIn(JobApplicationStatus.APPLIED));

		JobApplication moved = service.updateApplicationStatus("job_1", "app_1", USER,
			JobApplicationStatus.IN_REVIEW, null);

		assertThat(moved.status()).isEqualTo(JobApplicationStatus.IN_REVIEW);
		verify(repository).updateApplicationStatus(eq("app_1"), eq(JobApplicationStatus.IN_REVIEW), isNull(), any());
		verify(statusNotifier).notifyStatusChange(any(), any(), eq(JobApplicationStatus.IN_REVIEW));
	}

	@Test
	void walksTheWholeHappyPath() {
		// APPLIED -> IN_REVIEW -> ADVANCED, e o recuo de etapa tambem e permitido.
		JobApplication inReview = applicationIn(JobApplicationStatus.IN_REVIEW);
		when(repository.findByIdForUpdate("job_1")).thenReturn(java.util.Optional.of(openJob()));
		when(repository.findApplicationById("app_1")).thenReturn(java.util.Optional.of(inReview));

		assertThat(service.updateApplicationStatus("job_1", "app_1", USER, JobApplicationStatus.ADVANCED, null).status())
			.isEqualTo(JobApplicationStatus.ADVANCED);

		when(repository.findApplicationById("app_1"))
			.thenReturn(java.util.Optional.of(applicationIn(JobApplicationStatus.ADVANCED)));
		assertThat(service.updateApplicationStatus("job_1", "app_1", USER, JobApplicationStatus.IN_REVIEW, null).status())
			.isEqualTo(JobApplicationStatus.IN_REVIEW);
	}

	@Test
	void refusesATransitionThatSkipsAStage() {
		givenApplication(applicationIn(JobApplicationStatus.APPLIED));

		assertThatThrownBy(() -> service.updateApplicationStatus("job_1", "app_1", USER,
			JobApplicationStatus.ADVANCED, null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("nao pode passar");

		// Transicao invalida nao grava e NAO avisa o candidato.
		verify(repository, never()).updateApplicationStatus(any(), any(), any(), any());
		verifyNoInteractions(statusNotifier);
	}

	@Test
	void repeatingTheSameStatusIsRefusedAndDoesNotNotifyAgain() {
		givenApplication(applicationIn(JobApplicationStatus.IN_REVIEW));

		assertThatThrownBy(() -> service.updateApplicationStatus("job_1", "app_1", USER,
			JobApplicationStatus.IN_REVIEW, null))
			.isInstanceOf(ApiException.class);

		// E o que impede o clique repetido de virar mensagem repetida para o candidato.
		verifyNoInteractions(statusNotifier);
	}

	@Test
	void notSelectedIsTerminal() {
		givenApplication(applicationIn(JobApplicationStatus.NOT_SELECTED));

		assertThatThrownBy(() -> service.updateApplicationStatus("job_1", "app_1", USER,
			JobApplicationStatus.IN_REVIEW, null))
			.isInstanceOf(ApiException.class);

		verifyNoInteractions(statusNotifier);
	}

	@Test
	void refusesTriageFromSomeoneElse() {
		when(repository.findByIdForUpdate("job_1")).thenReturn(java.util.Optional.of(openJob()));

		assertThatThrownBy(() -> service.updateApplicationStatus("job_1", "app_1", "user_outro",
			JobApplicationStatus.IN_REVIEW, null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("So quem publicou");

		verify(repository, never()).updateApplicationStatus(any(), any(), any(), any());
		verifyNoInteractions(statusNotifier);
	}

	@Test
	void refusesAnApplicationThatBelongsToAnotherJob() {
		when(repository.findByIdForUpdate("job_1")).thenReturn(java.util.Optional.of(openJob()));
		// Candidatura de OUTRA vaga: o caminho da URL nao pode alcancar candidatura alheia.
		when(repository.findApplicationById("app_1")).thenReturn(java.util.Optional.of(
			JobApplication.create("app_1", "job_2", "user_candidato", "oi", "{}", java.time.Instant.now(),
				List.of())));

		assertThatThrownBy(() -> service.updateApplicationStatus("job_1", "app_1", USER,
			JobApplicationStatus.IN_REVIEW, null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("Candidatura nao encontrada");

		verifyNoInteractions(statusNotifier);
	}
}
