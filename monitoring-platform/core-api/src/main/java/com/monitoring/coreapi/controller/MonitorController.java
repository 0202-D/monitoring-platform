package com.monitoring.coreapi.controller;

import com.monitoring.coreapi.dto.CreateMonitorRequest;
import com.monitoring.coreapi.dto.MonitorResponse;
import com.monitoring.coreapi.dto.UpdateMonitorRequest;
import com.monitoring.coreapi.entity.AppUser;
import com.monitoring.coreapi.entity.Monitor;
import com.monitoring.coreapi.security.CurrentUserService;
import com.monitoring.coreapi.service.MonitorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/monitors")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public List<MonitorResponse> listMyMonitors(@AuthenticationPrincipal Jwt jwt) {
        AppUser user = currentUserService.resolveOrCreate(jwt);
        return monitorService.listMonitorsForUser(user.getId()).stream()
            .map(MonitorResponse::from)
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MonitorResponse createMonitor(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateMonitorRequest request) {

        AppUser user = currentUserService.resolveOrCreate(jwt);

        Monitor monitor = monitorService.createMonitor(
            user,
            Monitor.MonitorType.valueOf(request.type().name()),
            request.targetUrl(),
            request.checkIntervalSeconds()
        );

        return MonitorResponse.from(monitor);
    }

    @PutMapping("/{id}")
    public MonitorResponse updateMonitor(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateMonitorRequest request) {

        AppUser user = currentUserService.resolveOrCreate(jwt);

        Monitor monitor = monitorService.updateMonitor(
                id, user.getId(), request.targetUrl(),
                request.checkIntervalSeconds(), request.active()
        );

        return MonitorResponse.from(monitor);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMonitor(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        AppUser user = currentUserService.resolveOrCreate(jwt);
        monitorService.deleteMonitor(id, user.getId());
    }
}
