package backend.controller;

import backend.config.SecurityConfig;
import backend.dto.UserDTO;
import backend.security.AppUserDetailsService;
import backend.security.JwtService;
import backend.security.RestSecurityErrorHandler;
import backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vérifie l'application réelle de {@code SecurityConfig} (chaîne de filtres non désactivée, à la
 * différence de {@code ChatbotControllerTest}) : la gestion des utilisateurs (création/suppression)
 * doit être refusée à un CONSULTANT et autorisée à un ADMIN/SUPER_ADMIN. La lecture reste ouverte à
 * tout utilisateur authentifié (CSRF déjà désactivé globalement dans SecurityConfig).
 */
@WebMvcTest(UserController.class)
// RestSecurityErrorHandler importé réellement (pas mocké) : c'est lui qui écrit le 403/401 quand
// Spring Security refuse une requête (AccessDeniedHandler) — un mock ne ferait rien et laisserait
// passer un 200 par défaut, masquant un refus d'accès qui a pourtant bien eu lieu.
@Import({SecurityConfig.class, RestSecurityErrorHandler.class})
class UserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;
    // SecurityConfig.filterChain() en dépend : @WebMvcTest n'inclut pas les @Service arbitraires par
    // défaut. Jamais vraiment invoqués ici : @WithMockUser peuple le SecurityContext avant que
    // JwtAuthenticationFilter ne s'exécute, donc ces mocks ne font que satisfaire le câblage des beans.
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private AppUserDetailsService appUserDetailsService;

    @Test
    @WithMockUser(roles = "CONSULTANT")
    void create_asConsultant_isForbidden() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"email\":\"x@biat.tn\",\"password\":\"x12345678\",\"role\":\"CONSULTANT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_asAdmin_isAllowed() throws Exception {
        when(userService.create(any())).thenReturn(
                new UserDTO(1L, "x", "x@biat.tn", "x12345678", "CONSULTANT", null, true));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"email\":\"x@biat.tn\",\"password\":\"x12345678\",\"role\":\"CONSULTANT\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void delete_asSuperAdmin_isAllowed() throws Exception {
        mockMvc.perform(delete("/api/users/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "CONSULTANT")
    void delete_asConsultant_isForbidden() throws Exception {
        mockMvc.perform(delete("/api/users/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CONSULTANT")
    void findAll_asConsultant_isAllowed() throws Exception {
        when(userService.findAll(any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk());
    }
}
