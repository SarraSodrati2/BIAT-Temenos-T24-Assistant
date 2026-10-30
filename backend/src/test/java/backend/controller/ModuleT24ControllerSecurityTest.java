package backend.controller;

import backend.config.SecurityConfig;
import backend.dto.ModuleT24DTO;
import backend.security.AppUserDetailsService;
import backend.security.JwtService;
import backend.security.RestSecurityErrorHandler;
import backend.service.ModuleT24Service;
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
 * Lecture ouverte aux 3 rôles (accès en lecture seule explicitement demandé pour CONSULTANT),
 * écriture réservée à ADMIN/SUPER_ADMIN — même pattern que {@code UserControllerSecurityTest}.
 */
@WebMvcTest(ModuleT24Controller.class)
@Import({SecurityConfig.class, RestSecurityErrorHandler.class})
class ModuleT24ControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModuleT24Service moduleT24Service;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private AppUserDetailsService appUserDetailsService;

    @Test
    @WithMockUser(roles = "CONSULTANT")
    void findAll_asConsultant_isAllowed() throws Exception {
        when(moduleT24Service.findAll(any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/modules")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CONSULTANT")
    void create_asConsultant_isForbidden() throws Exception {
        mockMvc.perform(post("/api/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nomModule\":\"AA\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_asAdmin_isAllowed() throws Exception {
        when(moduleT24Service.create(any())).thenReturn(new ModuleT24DTO(1L, "AA"));

        mockMvc.perform(post("/api/modules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nomModule\":\"AA\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "CONSULTANT")
    void delete_asConsultant_isForbidden() throws Exception {
        mockMvc.perform(delete("/api/modules/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void delete_asSuperAdmin_isAllowed() throws Exception {
        mockMvc.perform(delete("/api/modules/1")).andExpect(status().isNoContent());
    }
}
