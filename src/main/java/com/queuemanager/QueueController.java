package com.queuemanager;

import com.queuemanager.Models.QueueInfo;
import com.queuemanager.Models.Token;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API. "userId" is set by AuthInterceptor from the JWT. */
@RestController
@RequestMapping("/api")
public class QueueController {
    private final QueueService service;

    public QueueController(QueueService service) {
        this.service = service;
    }

    // ----- queues -----

    @GetMapping("/queues")
    public List<QueueInfo> list(@RequestAttribute("userId") long userId) {
        return service.listQueues(userId);
    }

    @PostMapping("/queues")
    public ResponseEntity<QueueInfo> create(@RequestAttribute("userId") long userId,
                                            @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createQueue(userId, str(body, "name")));
    }

    @GetMapping("/queues/{id}")
    public Map<String, Object> state(@RequestAttribute("userId") long userId, @PathVariable("id") long id) {
        return service.queueState(id, userId);
    }

    @DeleteMapping("/queues/{id}")
    public Map<String, Boolean> delete(@RequestAttribute("userId") long userId, @PathVariable("id") long id) {
        service.deleteQueue(id, userId);
        return Map.of("ok", true);
    }

    @GetMapping("/queues/{id}/analytics")
    public Map<String, Object> analytics(@RequestAttribute("userId") long userId, @PathVariable("id") long id) {
        return service.analytics(id, userId);
    }

    // ----- tokens -----

    @PostMapping("/queues/{id}/tokens")
    public ResponseEntity<Token> addToken(@RequestAttribute("userId") long userId, @PathVariable("id") long id,
                                          @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addToken(id, userId, str(body, "name")));
    }

    @PostMapping("/queues/{id}/serve")
    public Token serve(@RequestAttribute("userId") long userId, @PathVariable("id") long id) {
        return service.serveNext(id, userId);
    }

    @PostMapping("/tokens/{id}/move")
    public Map<String, Boolean> move(@RequestAttribute("userId") long userId, @PathVariable("id") long id,
                                     @RequestBody Map<String, Object> body) {
        service.moveToken(id, userId, str(body, "direction"));
        return Map.of("ok", true);
    }

    @PostMapping("/tokens/{id}/cancel")
    public Map<String, Boolean> cancel(@RequestAttribute("userId") long userId, @PathVariable("id") long id) {
        service.cancelToken(id, userId);
        return Map.of("ok", true);
    }

    @PostMapping("/tokens/{id}/complete")
    public Map<String, Boolean> complete(@RequestAttribute("userId") long userId, @PathVariable("id") long id) {
        service.completeToken(id, userId);
        return Map.of("ok", true);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : v.toString();
    }
}
