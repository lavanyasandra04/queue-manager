package com.queuemanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** End-to-end API test against an in-memory H2 database (MySQL mode), so MySQL does not need to be running. Run with: mvn test */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class QueueApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String token;

    private record Res(int status, JsonNode body) {}

    private Res call(HttpMethod method, String url, Object body) throws Exception {
        MockHttpServletRequestBuilder rb = request(method, "/api" + url).contentType(MediaType.APPLICATION_JSON);
        if (token != null) rb.header("Authorization", "Bearer " + token);
        rb.content(body == null ? "{}" : om.writeValueAsString(body));
        MvcResult r = mvc.perform(rb).andReturn();
        String text = r.getResponse().getContentAsString();
        return new Res(r.getResponse().getStatus(), text.isEmpty() ? null : om.readTree(text));
    }

    private List<String> order(long queueId) throws Exception {
        List<String> names = new ArrayList<>();
        for (JsonNode t : call(HttpMethod.GET, "/queues/" + queueId, null).body.get("waiting")) {
            names.add(t.get("name").asText());
        }
        return names;
    }

    @Test
    void fullFlow() throws Exception {
        assertEquals(401, call(HttpMethod.GET, "/queues", null).status);

        token = call(HttpMethod.POST, "/auth/register", Map.of("username", "mgr", "password", "secret1"))
                .body.get("token").asText();
        assertEquals(401, call(HttpMethod.POST, "/auth/login", Map.of("username", "mgr", "password", "bad")).status);
        assertEquals(409, call(HttpMethod.POST, "/auth/register", Map.of("username", "mgr", "password", "secret1")).status);
        assertEquals(400, call(HttpMethod.POST, "/queues", Map.of("name", "  ")).status);

        long qid = call(HttpMethod.POST, "/queues", Map.of("name", "Billing Desk")).body.get("id").asLong();
        String[] names = {"Asha", "Ben", "Chitra", "Dev"};
        long[] ids = new long[4];
        for (int i = 0; i < names.length; i++) {
            JsonNode t = call(HttpMethod.POST, "/queues/" + qid + "/tokens", Map.of("name", names[i])).body;
            assertEquals(i + 1, t.get("number").asInt());
            ids[i] = t.get("id").asLong();
        }
        assertEquals(400, call(HttpMethod.POST, "/queues/" + qid + "/tokens", Map.of("name", " ")).status);

        // reorder
        call(HttpMethod.POST, "/tokens/" + ids[2] + "/move", Map.of("direction", "up"));
        assertEquals(List.of("Asha", "Chitra", "Ben", "Dev"), order(qid));
        call(HttpMethod.POST, "/tokens/" + ids[0] + "/move", Map.of("direction", "down"));
        assertEquals(List.of("Chitra", "Asha", "Ben", "Dev"), order(qid));
        assertEquals(400, call(HttpMethod.POST, "/tokens/" + ids[2] + "/move", Map.of("direction", "up")).status); // already first
        assertEquals(400, call(HttpMethod.POST, "/tokens/" + ids[3] + "/move", Map.of("direction", "down")).status); // already last

        // cancel
        assertEquals(200, call(HttpMethod.POST, "/tokens/" + ids[3] + "/cancel", null).status);
        assertEquals(400, call(HttpMethod.POST, "/tokens/" + ids[3] + "/cancel", null).status);

        // serve
        assertEquals("Chitra", call(HttpMethod.POST, "/queues/" + qid + "/serve", null).body.get("name").asText());
        assertEquals("Asha", call(HttpMethod.POST, "/queues/" + qid + "/serve", null).body.get("name").asText());
        JsonNode state = call(HttpMethod.GET, "/queues/" + qid, null).body;
        assertEquals(1, state.get("serving").size());
        int servedInHistory = 0;
        for (JsonNode t : state.get("history")) if ("served".equals(t.get("status").asText())) servedInHistory++;
        assertEquals(1, servedInHistory);

        call(HttpMethod.POST, "/queues/" + qid + "/serve", null); // Ben
        assertEquals(400, call(HttpMethod.POST, "/queues/" + qid + "/serve", null).status); // empty

        // analytics
        JsonNode a = call(HttpMethod.GET, "/queues/" + qid + "/analytics", null).body;
        assertEquals(1, a.get("totals").get("cancelled").asInt());
        assertEquals(2, a.get("totals").get("served").asInt());
        assertEquals(1, a.get("totals").get("serving").asInt());
        assertFalse(a.get("avgWaitMs").isNull());
        assertTrue(a.get("lengthTrend").size() > 0);

        // another manager cannot access this queue
        token = call(HttpMethod.POST, "/auth/register", Map.of("username", "other", "password", "secret1"))
                .body.get("token").asText();
        assertEquals(404, call(HttpMethod.GET, "/queues/" + qid, null).status);
        assertEquals(404, call(HttpMethod.POST, "/tokens/" + ids[0] + "/cancel", null).status);
    }
}
