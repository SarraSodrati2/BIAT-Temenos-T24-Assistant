package backend.controller;

import backend.dto.ChatAskRequest;
import backend.dto.ChatAskResponse;
import backend.service.ChatbotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vérifie que {@code POST /api/chat/ask} délègue bien à {@link ChatbotService} (donc, en pratique,
 * au pipeline RAG désormais implémenté par {@code ChatbotServiceImpl}) — au niveau HTTP/JSON, sans
 * dépendre d'Oracle/Ollama réels. La chaîne de filtres Spring Security est désactivée pour ce test
 * ciblé sur la couche MVC ({@code addFilters = false}) : la protection JWT elle-même n'est pas
 * concernée par cette couche RAG et reste inchangée dans {@code SecurityConfig}.
 */
@WebMvcTest(ChatbotController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChatbotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatbotService chatbotService;

    @Test
    void ask_delegatesToChatbotService_andReturnsItsAnswer() throws Exception {
        when(chatbotService.ask("Que fait AA.ARR.CHARGEOFF ?"))
                .thenReturn(new ChatAskResponse("Le module CHARGEOFF gère les créances irrécouvrables.", false, 1L, 2L));

        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatAskRequest("Que fait AA.ARR.CHARGEOFF ?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Le module CHARGEOFF gère les créances irrécouvrables."))
                .andExpect(jsonPath("$.authenticationRequired").value(false));

        verify(chatbotService).ask("Que fait AA.ARR.CHARGEOFF ?");
    }

    @Test
    void ask_authenticationRequired_isSurfacedInTheJsonResponse() throws Exception {
        when(chatbotService.ask("question"))
                .thenReturn(new ChatAskResponse("réponse quand même donnée", true, null, null));

        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatAskRequest("question"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("réponse quand même donnée"))
                .andExpect(jsonPath("$.authenticationRequired").value(true));
    }

    @Test
    void ask_blankQuestion_returns400WithoutCallingChatbotService() throws Exception {
        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatAskRequest(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ask_withChatId_forwardsItToChatbotService() throws Exception {
        when(chatbotService.ask("question", 42L)).thenReturn(new ChatAskResponse("ok", false, 1L, 2L));

        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatAskRequest("question", 42L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("ok"));

        verify(chatbotService).ask("question", 42L);
    }
}
