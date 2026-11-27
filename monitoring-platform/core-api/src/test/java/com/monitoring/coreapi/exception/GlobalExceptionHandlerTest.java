package com.monitoring.coreapi.exception;

import com.monitoring.coreapi.controller.MonitorController;
import com.monitoring.coreapi.security.CurrentUserService;
import com.monitoring.coreapi.service.MonitorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MonitorController.class)
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MonitorService monitorService;

    @MockBean
    private CurrentUserService currentUserService;

    @Test
    void responseStatusExceptionBecomesApiError() throws Exception {
        when(currentUserService.resolveOrCreate(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Монитор не найден"));

        mockMvc.perform(get("/api/monitors"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Монитор не найден"))
                .andExpect(jsonPath("$.path").value("/api/monitors"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void validationErrorReturnsFieldViolations() throws Exception {
        mockMvc.perform(post("/api/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"HTTP","targetUrl":"http://example.com","checkIntervalSeconds":30}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.errors[0].field").value("checkIntervalSeconds"));
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Некорректное тело запроса"));
    }

    @Test
    void pathVariableTypeMismatchReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/api/monitors/not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetUrl":"http://example.com","checkIntervalSeconds":120,"active":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Некорректный тип параметра 'id'"));
    }
}