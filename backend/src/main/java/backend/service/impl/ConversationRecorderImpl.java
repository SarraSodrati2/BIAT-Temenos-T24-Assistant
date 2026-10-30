package backend.service.impl;

import backend.dto.ChatDTO;
import backend.dto.ChatHistoryDTO;
import backend.dto.ConversationRecordingResult;
import backend.entity.User;
import backend.security.CurrentUserResolver;
import backend.service.ChatHistoryService;
import backend.service.ChatService;
import backend.service.ConversationRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationRecorderImpl implements ConversationRecorder {

    private static final String MESSAGE_TYPE_USER = "USER";
    private static final String MESSAGE_TYPE_ASSISTANT = "ASSISTANT";
    private static final int TITLE_MAX_LENGTH = 60;

    private final CurrentUserResolver currentUserResolver;
    private final ChatService chatService;
    private final ChatHistoryService chatHistoryService;

    @Override
    public ConversationRecordingResult recordUserMessage(String question) {
        return recordUserMessage(question, null);
    }

    @Override
    public ConversationRecordingResult recordUserMessage(String question, Long requestedChatId) {
        Optional<Long> userId;
        try {
            userId = currentUserId();
        } catch (Exception e) {
            log.error("Échec de la résolution de l'utilisateur authentifié (erreur interne, pas un problème d'authentification), "
                    + "le pipeline RAG continue sans historique.", e);
            return ConversationRecordingResult.notPersistedDueToInternalError();
        }

        if (userId.isEmpty()) {
            // Cas explicitement détecté et distingué d'un échec interne : JWT absent, invalide, expiré,
            // ou authentification anonyme (AnonymousAuthenticationToken) — voir les logs détaillés déjà
            // émis par currentUserId(). Le frontend reçoit authenticationRequired=true et peut inviter
            // l'utilisateur à se reconnecter ; le pipeline RAG continue de répondre normalement.
            log.warn("Authentification requise : conversation non enregistrée, le frontend doit inviter l'utilisateur à se reconnecter.");
            return ConversationRecordingResult.authenticationRequired();
        }

        try {
            log.info("[DIAG] currentUserId() = {}", userId.get());
            Long chatId = resolveChat(userId.get(), question, requestedChatId);
            log.info("[DIAG] chatId retenu (créé ou retrouvé) = {}", chatId);
            Optional<ChatHistoryDTO> unanswered = unansweredDuplicate(chatId, question);
            if (unanswered.isPresent()) {
                log.info("Question identique déjà enregistrée sans réponse dans le chat {} (messageId={}) : réutilisée, "
                        + "pas de doublon en base.", chatId, unanswered.get().getId());
                return ConversationRecordingResult.persisted(chatId, unanswered.get().getId());
            }
            ChatHistoryDTO saved = chatHistoryService.create(new ChatHistoryDTO(null, question, MESSAGE_TYPE_USER, null, chatId));
            log.info("[DIAG] recordUserMessage terminé sans exception, chatId={}, messageId={}", chatId, saved.getId());
            return ConversationRecordingResult.persisted(chatId, saved.getId());
        } catch (Exception e) {
            log.error("Échec de la persistance de la question dans CHAT_HISTORY (utilisateur pourtant authentifié, "
                    + "erreur interne, ex. Oracle indisponible), le pipeline RAG continue sans historique.", e);
            return ConversationRecordingResult.notPersistedDueToInternalError();
        }
    }

    @Override
    public Long recordAssistantMessage(Long chatId, String answer) {
        try {
            ChatHistoryDTO saved = chatHistoryService.create(new ChatHistoryDTO(null, answer, MESSAGE_TYPE_ASSISTANT, null, chatId));
            return saved.getId();
        } catch (Exception e) {
            log.error("Échec de la persistance de la réponse dans CHAT_HISTORY.", e);
            return null;
        }
    }

    private Optional<Long> currentUserId() {
        return currentUserResolver.resolve().map(User::getId);
    }

    /**
     * Dernier message de la conversation s'il est une question USER identique à {@code question} (donc
     * jamais répondue : une réponse enregistrée serait le dernier message) — cas d'une tentative
     * interrompue (Stop, timeout, onglet fermé, « Réessayer ») que le client relance. Best-effort :
     * toute erreur de lecture retombe sur « pas de doublon détecté ».
     */
    private Optional<ChatHistoryDTO> unansweredDuplicate(Long chatId, String question) {
        try {
            Page<ChatHistoryDTO> last = chatHistoryService.findByChat(chatId,
                    PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
            if (last == null || !last.hasContent()) {
                return Optional.empty();
            }
            ChatHistoryDTO message = last.getContent().get(0);
            boolean sameQuestion = MESSAGE_TYPE_USER.equals(message.getMessageType())
                    && message.getContent() != null && message.getContent().trim().equals(question.trim());
            return sameQuestion ? Optional.of(message) : Optional.empty();
        } catch (Exception e) {
            log.warn("Impossible de vérifier un doublon de question dans le chat {} : enregistrement normal.", chatId, e);
            return Optional.empty();
        }
    }

    /**
     * Conversation ciblée par le client si elle appartient à l'utilisateur ; sinon (aucune précisée,
     * inconnue ou d'un autre utilisateur) le chat le plus récent de l'utilisateur s'il existe, sinon un
     * nouveau chat.
     */
    private Long resolveChat(Long userId, String question, Long requestedChatId) {
        if (requestedChatId != null) {
            try {
                ChatDTO requested = chatService.findById(requestedChatId);
                if (userId.equals(requested.getUserId())) {
                    return requested.getId();
                }
                log.warn("Chat {} demandé par le client n'appartient pas à l'utilisateur {} : ignoré.", requestedChatId, userId);
            } catch (Exception e) {
                log.warn("Chat {} demandé par le client inaccessible ({}) : repli sur la conversation la plus récente.",
                        requestedChatId, e.getMessage());
            }
        }
        Page<ChatDTO> existing = chatService.findByUser(userId,
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "updatedAt")));
        if (existing.hasContent()) {
            Long id = existing.getContent().get(0).getId();
            log.info("[DIAG] Chat existant retrouvé pour userId={} : chatId={}", userId, id);
            return id;
        }
        log.info("[DIAG] Aucun chat existant pour userId={} : création d'un nouveau chat.", userId);
        ChatDTO created = chatService.create(
                new ChatDTO(null, defaultTitle(question), null, null, userId, null, null, null, null));
        log.info("[DIAG] Nouveau chat créé : chatId={}", created.getId());
        return created.getId();
    }

    private static String defaultTitle(String question) {
        String trimmed = question.trim();
        return trimmed.length() > TITLE_MAX_LENGTH ? trimmed.substring(0, TITLE_MAX_LENGTH) : trimmed;
    }
}
