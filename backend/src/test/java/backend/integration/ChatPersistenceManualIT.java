package backend.integration;

import backend.dto.ChatAskRequest;
import backend.dto.ChatAskResponse;
import backend.entity.Chat;
import backend.entity.ChatHistory;
import backend.entity.User;
import backend.repository.ChatHistoryRepository;
import backend.repository.ChatRepository;
import backend.repository.UserRepository;
import backend.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérification manuelle bout-en-bout de la persistance des conversations : {@code POST
 * /api/chat/ask}, authentifié par un vrai JWT ({@link JwtService}, comme émis par {@code POST
 * /login}), doit créer une ligne dans {@code CHATS} et deux lignes dans {@code CHAT_HISTORY}
 * ({@code MESSAGE_TYPE = USER} puis {@code ASSISTANT}) pour l'utilisateur du token — sans que le
 * pipeline RAG lui-même (déjà couvert par {@code RagPipelineManualIT}) ne soit affecté.
 *
 * <p>Mêmes garde-fous que {@code RagPipelineManualIT} : nommée en {@code *IT} (exclue de
 * {@code mvn test}), gatée par {@code -Drag.manual.test=true}, nécessite Oracle + Ollama réels
 * (profil par défaut actif, pas {@code --spring.profiles.active=dev}) :
 * <pre>
 * mvn test -Dtest=ChatPersistenceManualIT -Drag.manual.test=true
 * </pre>
 *
 * <p>Crée un utilisateur de test jetable ({@value #TEST_USERNAME}) et le supprime (en cascade sur
 * ses chats/messages, {@code orphanRemoval = true} sur {@link Chat#getChatHistories()}) après
 * chaque test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "rag.manual.test", matches = "true")
class ChatPersistenceManualIT {

    private static final String TEST_USERNAME = "it.persistence.test";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ChatRepository chatRepository;
    @Autowired
    private ChatHistoryRepository chatHistoryRepository;
    @Autowired
    private JwtService jwtService;

    @AfterEach
    void cleanUp() {
        userRepository.findByUsername(TEST_USERNAME).ifPresent(user -> {
            chatRepository.findByUserId(user.getId(), Pageable.unpaged()).forEach(chatRepository::delete);
            userRepository.delete(user);
        });
    }

    @Test
    void ask_persistsTheQuestionAndTheAnswerForTheAuthenticatedUser() {
        User user = userRepository.save(User.builder()
                .username(TEST_USERNAME)
                .email("it.persistence.test@biat.tn")
                .password("N/A - JWT généré directement dans le test, pas de POST /login")
                .role("CONSULTANT")
                .isActive(true)
                .build());
        String token = jwtService.generateToken(user);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<ChatAskRequest> request = new HttpEntity<>(new ChatAskRequest("Résume CUSTOMER"), headers);

        ResponseEntity<ChatAskResponse> response =
                restTemplate.postForEntity("/api/chat/ask", request, ChatAskResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        System.out.println("--- Réponse ---\n" + response.getBody().getAnswer());
        assertThat(response.getBody().isAuthenticationRequired())
                .as("Utilisateur authentifié : pas de demande de reconnexion")
                .isFalse();

        List<Chat> chats = chatRepository.findByUserId(user.getId(), Pageable.unpaged()).getContent();
        assertThat(chats).as("SELECT * FROM CHATS doit contenir une conversation pour cet utilisateur").hasSize(1);

        List<ChatHistory> history = chatHistoryRepository
                .findByChatId(chats.get(0).getId(), Pageable.unpaged()).getContent();
        assertThat(history)
                .as("SELECT * FROM CHAT_HISTORY doit contenir les messages USER et ASSISTANT")
                .extracting(ChatHistory::getMessageType)
                .containsExactlyInAnyOrder("USER", "ASSISTANT");
        assertThat(history)
                .filteredOn(h -> h.getMessageType().equals("USER"))
                .extracting(ChatHistory::getContent)
                .containsExactly("Résume CUSTOMER");
    }

    /**
     * Reproduit la cause exacte de "les conversations ne sont jamais enregistrées" observée en usage
     * réel : {@code /api/chat/ask} est une route publique ({@code SecurityConfig.PUBLIC_ROUTES}), donc
     * un token absent/expiré/invalide n'est PAS rejeté (pas de 401) — {@link
     * backend.security.JwtAuthenticationFilter} laisse simplement le {@code SecurityContext} vide et la
     * requête continue. Le chatbot répond normalement (200, bonne réponse RAG) mais {@code
     * ConversationRecorderImpl.currentUserId()} ne trouve personne à qui rattacher la conversation :
     * aucune ligne CHATS/CHAT_HISTORY n'est créée. Le frontend reçoit désormais {@code
     * authenticationRequired = true} dans la réponse pour l'inviter explicitement à se reconnecter,
     * au lieu d'un échec totalement silencieux.
     */
    @Test
    void ask_invalidToken_stillAnswersSuccessfully_butPersistsNothing() {
        User user = userRepository.save(User.builder()
                .username(TEST_USERNAME)
                .email("it.persistence.test@biat.tn")
                .password("N/A")
                .role("CONSULTANT")
                .isActive(true)
                .build());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("token-invalide-ou-expire");
        HttpEntity<ChatAskRequest> request = new HttpEntity<>(new ChatAskRequest("Résume CUSTOMER"), headers);

        ResponseEntity<ChatAskResponse> response =
                restTemplate.postForEntity("/api/chat/ask", request, ChatAskResponse.class);

        assertThat(response.getStatusCode())
                .as("Route publique : un token invalide ne doit pas provoquer un 401")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getAnswer()).isNotBlank();
        assertThat(response.getBody().isAuthenticationRequired())
                .as("Le frontend doit être informé qu'il faut se reconnecter pour que l'historique soit enregistré")
                .isTrue();

        List<Chat> chats = chatRepository.findByUserId(user.getId(), Pageable.unpaged()).getContent();
        assertThat(chats).as("Aucune conversation ne doit être créée sans utilisateur authentifié résolu").isEmpty();
    }
}
