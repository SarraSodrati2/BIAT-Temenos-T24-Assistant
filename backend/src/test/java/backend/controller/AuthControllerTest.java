package backend.controller;

import backend.config.SecurityConfig;
import backend.dto.RegisterRequest;
import backend.dto.UserDTO;
import backend.repository.UserRepository;
import backend.security.AppUserDetailsService;
import backend.security.JwtService;
import backend.security.RestSecurityErrorHandler;
import backend.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /register} : route publique (voir {@code SecurityConfig.PUBLIC_ROUTES}) — aucune de
 * ces requêtes n'envoie de token, ce qui vérifie au passage que la route est bien accessible sans
 * authentification (chaîne de filtres réelle importée, comme {@code UserControllerSecurityTest}).
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, RestSecurityErrorHandler.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private AuthenticationManager authenticationManager;
    // SecurityConfig.filterChain() en dépend, jamais invoqués (pas de header Authorization envoyé).
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private AppUserDetailsService appUserDetailsService;

    @Test
    void register_passwordMismatch_returns400_withoutCallingUserService() throws Exception {
        RegisterRequest request = new RegisterRequest("alice", "alice@biat.tn", "password123", "different456");

        mockMvc.perform(post("/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoUserServiceCreate();
    }

    @Test
    void register_usernameAlreadyTaken_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest("alice", "alice@biat.tn", "password123", "password123");
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        mockMvc.perform(post("/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoUserServiceCreate();
    }

    @Test
    void register_emailAlreadyTaken_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest("alice", "alice@biat.tn", "password123", "password123");
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@biat.tn")).thenReturn(true);

        mockMvc.perform(post("/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verifyNoUserServiceCreate();
    }

    @Test
    void register_success_forcesConsultantRole_regardlessOfClientInput() throws Exception {
        RegisterRequest request = new RegisterRequest("alice", "alice@biat.tn", "password123", "password123");
        when(userService.create(any())).thenReturn(
                new UserDTO(1L, "alice", "alice@biat.tn", null, "CONSULTANT", null, true));

        mockMvc.perform(post("/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CONSULTANT"));

        org.mockito.ArgumentCaptor<UserDTO> captor = org.mockito.ArgumentCaptor.forClass(UserDTO.class);
        verify(userService).create(captor.capture());
        // RegisterRequest n'a pas de champ role : impossible de transmettre autre chose ici.
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getRole()).isEqualTo("CONSULTANT");
    }

    /** Preuve que POST /register n'exige aucun en-tête Authorization (route publique). */
    @Test
    void register_noAuthorizationHeaderSent_stillReaches200Range() throws Exception {
        RegisterRequest request = new RegisterRequest("bob", "bob@biat.tn", "password123", "password123");
        when(userService.create(any())).thenReturn(
                new UserDTO(2L, "bob", "bob@biat.tn", null, "CONSULTANT", null, true));

        mockMvc.perform(post("/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private void verifyNoUserServiceCreate() {
        verify(userService, org.mockito.Mockito.never()).create(any());
    }
}
