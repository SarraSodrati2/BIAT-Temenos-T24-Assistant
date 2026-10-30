package backend.controller;

import backend.config.SecurityConfig;
import backend.dto.TableT24DTO;
import backend.security.AppUserDetailsService;
import backend.security.JwtService;
import backend.security.RestSecurityErrorHandler;
import backend.service.TableT24Service;
import backend.service.XmlParserService;
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
 * Lecture (y compris consultation des imports XML déjà stockés) ouverte aux 3 rôles, écriture
 * (créer/modifier/supprimer une table = import XML) réservée à ADMIN/SUPER_ADMIN.
 */
@WebMvcTest(TableT24Controller.class)
@Import({SecurityConfig.class, RestSecurityErrorHandler.class})
class TableT24ControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TableT24Service tableT24Service;
    @MockitoBean
    private XmlParserService xmlParserService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private AppUserDetailsService appUserDetailsService;

    @Test
    @WithMockUser(roles = "CONSULTANT")
    void findAll_asConsultant_isAllowed() throws Exception {
        when(tableT24Service.findAll(any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/table-t24")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CONSULTANT")
    void create_asConsultant_isForbidden() throws Exception {
        mockMvc.perform(post("/api/table-t24")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nomTable\":\"CUSTOMER\",\"data\":\"<xml/>\",\"moduleId\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_asAdmin_isAllowed() throws Exception {
        when(tableT24Service.create(any())).thenReturn(
                new TableT24DTO(1L, "CUSTOMER", "<xml/>", null, null, 1L));

        mockMvc.perform(post("/api/table-t24")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nomTable\":\"CUSTOMER\",\"data\":\"<xml/>\",\"moduleId\":1}"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "CONSULTANT")
    void delete_asConsultant_isForbidden() throws Exception {
        mockMvc.perform(delete("/api/table-t24/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    void delete_asSuperAdmin_isAllowed() throws Exception {
        mockMvc.perform(delete("/api/table-t24/1")).andExpect(status().isNoContent());
    }
}
