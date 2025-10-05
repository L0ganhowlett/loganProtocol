package org.logan.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class BedrockAdminController {

    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    // kernel base URL, e.g. "http://localhost:8080"
    public BedrockAdminController(@Value("${kernel.base-url}") String kernelBaseUrl) {
        this.rest = new RestTemplate();
        this.kernelBaseUrl = kernelBaseUrl;
    }

    private final String kernelBaseUrl;

    public static record SpawnRequest(String agentId, String agentType) {}
    public static record KernelResponse(boolean ok, String message, Map<String,Object> data) {}

    /**
     * Request kernel to spawn a new agent.
     * Example: POST /admin/request-spawn { "agentType":"BEDROCK", "agentId":"optional-id" }
     */
    @PostMapping("/request-spawn")
    public ResponseEntity<?> requestSpawn(@RequestBody SpawnRequest req) {
        try {
            String url = kernelBaseUrl + "/agents";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String,Object> body = Map.of(
                    "type", req.agentType(),
                    "id", req.agentId()
            );

            HttpEntity<Map<String,Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = rest.exchange(url, HttpMethod.POST, entity, Map.class);
            return ResponseEntity.status(resp.getStatusCode()).body(resp.getBody());

        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("ok", false, "message", ex.getMessage()));
        }
    }

    /**
     * Request kernel to terminate an agent.
     * Example: POST /admin/request-terminate { "agentId":"id-to-kill" }
     */
    @PostMapping("/request-terminate")
    public ResponseEntity<?> requestTerminate(@RequestBody Map<String,String> body) {
        String agentId = body.get("agentId");
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "agentId required"));
        }

        try {
            String url = kernelBaseUrl + "/agents/" + agentId;
            ResponseEntity<Map> resp = rest.exchange(url, HttpMethod.DELETE, null, Map.class);
            return ResponseEntity.status(resp.getStatusCode()).body(resp.getBody());
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("ok", false, "message", ex.getMessage()));
        }
    }

    /**
     * Request kernel to deregister and then optionally shut down this process.
     * Example: POST /admin/self-terminate  { "graceful": true }
     */
    @PostMapping("/self-terminate")
    public ResponseEntity<?> selfTerminate(@RequestBody(required = false) Map<String,Object> body) {
        boolean graceful = true;
        String myAgentId = (body != null && body.containsKey("agentId")) ? (String) body.get("agentId") : null;

        try {
            if (myAgentId != null) {
                // ask kernel to mark it terminated
                String url = kernelBaseUrl + "/agents/" + myAgentId;
                rest.exchange(url, HttpMethod.DELETE, null, Map.class);
            }

            // respond BEFORE exiting to let caller know we accepted the request
            Map<String,Object> resp = Map.of("ok", true, "message", "shutting down");
            // spawn a thread to exit after short delay so HTTP can return
            new Thread(() -> {
                try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                System.exit(0);
            }).start();
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("ok", false, "message", ex.getMessage()));
        }
    }
}
