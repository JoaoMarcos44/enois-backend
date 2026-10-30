package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMessageJpaRepository extends JpaRepository<MessageJpaEntity, String> {

	List<MessageJpaEntity> findByConversationIdOrderByCreatedAtDesc(String conversationId,
			org.springframework.data.domain.Pageable pageable);

	List<MessageJpaEntity> findByConversationIdAndCreatedAtLessThanOrderByCreatedAtDesc(String conversationId,
			java.time.Instant before, org.springframework.data.domain.Pageable pageable);

	Optional<MessageJpaEntity> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);

	Optional<MessageJpaEntity>
		findFirstByConversationIdAndSenderIdAndTextAndCreatedAtAfterOrderByCreatedAtDesc(
			String conversationId, String senderId, String text, java.time.Instant since);

	/** Mensagens da conversa SEM as que este leitor apagou para si (filtro no banco: a paginacao
	 *  continua coerente, e nao sobra buraco na pagina). */
	@Query("""
			SELECT m FROM MessageJpaEntity m
			WHERE m.conversationId = :conversationId
			AND NOT EXISTS (SELECT h FROM MessageHiddenJpaEntity h
				WHERE h.messageId = m.id AND h.userId = :viewerId)
			ORDER BY m.createdAt DESC, m.id DESC""")
	List<MessageJpaEntity> visibleMessages(@Param("conversationId") String conversationId,
			@Param("viewerId") String viewerId, Pageable pageable);

	@Query("""
			SELECT m FROM MessageJpaEntity m
			WHERE m.conversationId = :conversationId
			AND (m.createdAt < :before OR (m.createdAt = :before AND m.id < :beforeId))
			AND NOT EXISTS (SELECT h FROM MessageHiddenJpaEntity h
				WHERE h.messageId = m.id AND h.userId = :viewerId)
			ORDER BY m.createdAt DESC, m.id DESC""")
	List<MessageJpaEntity> visibleMessagesBefore(@Param("conversationId") String conversationId,
			@Param("viewerId") String viewerId, @Param("before") java.time.Instant before,
			@Param("beforeId") String beforeId, Pageable pageable);

	@Query("""
			SELECT COUNT(m) FROM MessageJpaEntity m
			WHERE m.conversationId = :conversationId
			AND m.createdAt > :lastReadAt""")
	int countUnreadAfter(@Param("conversationId") String conversationId, @Param("lastReadAt") java.time.Instant lastReadAt);

	@Query("SELECT COUNT(m) FROM MessageJpaEntity m WHERE m.conversationId = :conversationId AND m.id IN :messageIds")
	long countInConversation(@Param("conversationId") String conversationId,
			@Param("messageIds") java.util.Collection<String> messageIds);

	int countByConversationId(String conversationId);

	List<MessageJpaEntity> findBySenderId(String senderId);
}
