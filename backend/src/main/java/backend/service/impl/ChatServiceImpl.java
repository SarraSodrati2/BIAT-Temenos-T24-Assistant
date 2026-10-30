package backend.service.impl;

import backend.dto.ChatContextUpdateDTO;
import backend.dto.ChatDTO;
import backend.entity.Chat;
import backend.entity.User;
import backend.exception.ResourceNotFoundException;
import backend.repository.ChatHistoryRepository;
import backend.repository.ChatRepository;
import backend.repository.UserRepository;
import backend.security.CurrentUserResolver;
import backend.service.ChatService;
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
public class ChatServiceImpl implements ChatService {

    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private final ChatRepository chatRepository;
    private final UserRepository userRepository;
    private final ChatHistoryRepository chatHistoryRepository;
    private final CurrentUserResolver currentUserResolver;

    /**
     * SUPER_ADMIN consulte les conversations de n'importe quel utilisateur (droit explicitement
     * accordé) ; CONSULTANT et ADMIN ne peuvent consulter que les leurs — sans cette vérification,
     * n'importe quel utilisateur authentifié pouvait lire l'historique de n'importe qui d'autre en
     * changeant simplement le paramètre {@code userId}.
     */
    @Override
    public Page<ChatDTO> findByUser(Long userId, Pageable pageable) {
        User actor = requireCurrentUser();
        if (!ROLE_SUPER_ADMIN.equals(actor.getRole()) && !actor.getId().equals(userId)) {
            throw new AccessDeniedException("Vous ne pouvez consulter que vos propres conversations.");
        }
        return chatRepository.findByUserId(userId, pageable).map(this::toDto);
    }

    /** Même règle d'appartenance que {@link #findByUser} : un chat précis reste privé à son propriétaire. */
    @Override
    public ChatDTO findById(Long id) {
        Chat chat = getEntity(id);
        assertOwnership(chat);
        return toDto(chat);
    }

    /** Un utilisateur ne peut créer une conversation que pour lui-même (sauf SUPER_ADMIN). */
    @Override
    @Transactional
    public ChatDTO create(ChatDTO dto) {
        User actor = requireCurrentUser();
        if (!ROLE_SUPER_ADMIN.equals(actor.getRole()) && !actor.getId().equals(dto.getUserId())) {
            throw new AccessDeniedException("Vous ne pouvez créer une conversation que pour vous-même.");
        }
        log.info("[DIAG] ChatService.create() - DTO reçu : title='{}', userId={}", dto.getTitle(), dto.getUserId());
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + dto.getUserId()));
        Chat chat = new Chat();
        chat.setTitle(dto.getTitle());
        chat.setUser(user);
        log.info("[DIAG] ChatService.create() - entité Chat construite (avant save), id=null (pas encore persistée).");
        Chat saved = chatRepository.save(chat);
        // GenerationType.IDENTITY : l'INSERT est exécuté immédiatement par Hibernate pour obtenir cet
        // ID (pas de report possible en fin de transaction) - le voir ici prouve que l'INSERT a eu lieu.
        log.info("[DIAG] ChatService.create() - chatRepository.save() retourné, id généré = {}", saved.getId());
        return toDto(saved);
    }

    /** Renommer (update) reste réservé au propriétaire du chat, même règle que la consultation. */
    @Override
    @Transactional
    public ChatDTO update(Long id, ChatDTO dto) {
        Chat chat = getEntity(id);
        assertOwnership(chat);
        chat.setTitle(dto.getTitle());
        return toDto(chatRepository.save(chat));
    }

    /** Fixer/changer le contexte (module/table/champ) reste réservé au propriétaire, même règle que update(). */
    @Override
    @Transactional
    public ChatDTO updateContext(Long id, ChatContextUpdateDTO dto) {
        Chat chat = getEntity(id);
        assertOwnership(chat);
        chat.setSelectedModule(dto.getModule());
        chat.setSelectedTable(dto.getTable());
        chat.setSelectedField(dto.getField());
        return toDto(chatRepository.save(chat));
    }

    /** Supprimer reste réservé au propriétaire du chat, même règle que la consultation/le renommage. */
    @Override
    @Transactional
    public void delete(Long id) {
        Chat chat = getEntity(id);
        assertOwnership(chat);
        chatRepository.delete(chat);
    }

    private User requireCurrentUser() {
        return currentUserResolver.resolve()
                .orElseThrow(() -> new AccessDeniedException("Authentification requise."));
    }

    private void assertOwnership(Chat chat) {
        User actor = requireCurrentUser();
        if (!ROLE_SUPER_ADMIN.equals(actor.getRole()) && !actor.getId().equals(chat.getUser().getId())) {
            throw new AccessDeniedException("Vous ne pouvez agir que sur vos propres conversations.");
        }
    }

    private Chat getEntity(Long id) {
        return chatRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found: " + id));
    }

    private ChatDTO toDto(Chat chat) {
        return new ChatDTO(chat.getId(), chat.getTitle(), chat.getCreatedAt(), chat.getUpdatedAt(),
                chat.getUser().getId(), chatHistoryRepository.countByChatId(chat.getId()),
                chat.getSelectedModule(), chat.getSelectedTable(), chat.getSelectedField());
    }
}
