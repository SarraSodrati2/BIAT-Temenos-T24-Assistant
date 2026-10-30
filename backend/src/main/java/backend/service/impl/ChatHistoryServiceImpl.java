package backend.service.impl;

import backend.dto.ChatHistoryDTO;
import backend.entity.Chat;
import backend.entity.ChatHistory;
import backend.entity.User;
import backend.exception.ResourceNotFoundException;
import backend.repository.ChatHistoryRepository;
import backend.repository.ChatRepository;
import backend.security.CurrentUserResolver;
import backend.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatHistoryServiceImpl implements ChatHistoryService {

    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private final ChatHistoryRepository chatHistoryRepository;
    private final ChatRepository chatRepository;
    private final CurrentUserResolver currentUserResolver;

    /** Même règle d'appartenance que {@code ChatServiceImpl.findByUser} (voir sa Javadoc). */
    @Override
    public Page<ChatHistoryDTO> findByChat(Long chatId, Pageable pageable) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found: " + chatId));
        User actor = currentUserResolver.resolve()
                .orElseThrow(() -> new AccessDeniedException("Authentification requise."));
        if (!ROLE_SUPER_ADMIN.equals(actor.getRole()) && !actor.getId().equals(chat.getUser().getId())) {
            throw new AccessDeniedException("Vous ne pouvez consulter que vos propres conversations.");
        }
        return chatHistoryRepository.findByChatId(chatId, pageable).map(this::toDto);
    }

    @Override
    public ChatHistoryDTO findById(Long id) {
        return toDto(getEntity(id));
    }

    @Override
    @Transactional
    public ChatHistoryDTO create(ChatHistoryDTO dto) {
        log.info("[DIAG] ChatHistoryService.create() - DTO reçu : chatId={}, messageType={}, content(len)={}",
                dto.getChatId(), dto.getMessageType(), dto.getContent() != null ? dto.getContent().length() : -1);
        Chat chat = chatRepository.findById(dto.getChatId())
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found: " + dto.getChatId()));
        ChatHistory history = new ChatHistory();
        history.setContent(dto.getContent());
        history.setMessageType(dto.getMessageType());
        history.setChat(chat);
        ChatHistory saved = chatHistoryRepository.save(history);
        // GenerationType.IDENTITY : l'INSERT est exécuté immédiatement pour obtenir cet ID.
        log.info("[DIAG] ChatHistoryService.create() - chatHistoryRepository.save() retourné, id généré = {}", saved.getId());
        return toDto(saved);
    }

    /** Même règle d'appartenance que {@link #findByChat} : supprimer un message reste réservé à son
     * propriétaire (jusqu'ici absent, alors que ce endpoint est maintenant exposé par un bouton
     * "Supprimer" côté chat — n'importe quel utilisateur authentifié pouvait supprimer le message de
     * n'importe qui d'autre en devinant/incrémentant un id). */
    @Override
    @Transactional
    public void delete(Long id) {
        ChatHistory history = getEntity(id);
        User actor = currentUserResolver.resolve()
                .orElseThrow(() -> new AccessDeniedException("Authentification requise."));
        if (!ROLE_SUPER_ADMIN.equals(actor.getRole()) && !actor.getId().equals(history.getChat().getUser().getId())) {
            throw new AccessDeniedException("Vous ne pouvez supprimer que vos propres messages.");
        }
        chatHistoryRepository.delete(history);
    }

    private ChatHistory getEntity(Long id) {
        return chatHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ChatHistory not found: " + id));
    }

    private ChatHistoryDTO toDto(ChatHistory history) {
        return new ChatHistoryDTO(history.getId(), history.getContent(), history.getMessageType(),
                history.getCreatedAt(), history.getChat().getId());
    }
}
