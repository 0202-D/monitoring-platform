package com.monitoring.notificationservice.exception;

import com.monitoring.notificationservice.channel.EmailChannelController;
import com.monitoring.notificationservice.channel.EmailVerificationService;
import com.monitoring.notificationservice.channel.NotificationChannelController;
import com.monitoring.notificationservice.channel.TelegramLinkService;
import com.monitoring.notificationservice.channel.UserChannelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {NotificationChannelController.class, EmailChannelController.class})
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TelegramLinkService telegramLinkService;

    @MockBean
    private UserChannelRepository channelRepository;

    @MockBean
    private EmailVerificationService emailVerificationService;

    @Test
    void unknownChannelReturnsApiError() throws Exception {
        mockMvc.perform(put("/api/notification-channels/SMS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Неизвестный канал: SMS"))
                .andExpect(jsonPath("$.path").value("/api/notification-channels/SMS"));
    }

    @Test
    void serviceNotFoundBecomesApiError() throws Exception {
        when(emailVerificationService.confirm(anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Ссылка недействительна или устарела"));

        mockMvc.perform(post("/api/email-verification/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Ссылка недействительна или устарела"));
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/api/notification-channels/TELEGRAM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Некорректное тело запроса"));
    }
}