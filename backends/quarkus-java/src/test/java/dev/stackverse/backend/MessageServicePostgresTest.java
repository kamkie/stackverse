package dev.stackverse.backend;

import static dev.stackverse.backend.PostgresTestSupport.BASE_TIME;
import static dev.stackverse.backend.PostgresTestSupport.authorization;
import static dev.stackverse.backend.PostgresTestSupport.insertMessage;
import static dev.stackverse.backend.PostgresTestSupport.request;
import static dev.stackverse.backend.PostgresTestSupport.reset;
import static dev.stackverse.backend.PostgresTestSupport.scalarLong;
import static dev.stackverse.backend.PostgresTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(PostgresTestProfile.class)
class MessageServicePostgresTest {
    private static final UUID TITLE_EN = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    private static final UUID TITLE_PL = UUID.fromString("aaaaaaaa-2222-2222-2222-222222222222");
    private static final UUID ONLY_EN = UUID.fromString("aaaaaaaa-3333-3333-3333-333333333333");

    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;

    @BeforeEach
    void clearDatabase() throws SQLException {
        reset(dataSource);
    }

    @Test
    void listsAndBundlesLocalizedMessagesWithDeterministicEtagRevalidation() throws Exception {
        seedMessages();
        MessageService service = service(null);
        Map<String, List<String>> listQuery =
                Map.of(
                        "language", List.of("en"),
                        "q", List.of("title"),
                        "page", List.of("0"),
                        "size", List.of("5"));

        Response listed = service.listMessages(request(listQuery));
        JsonNode page = jsonBody(listed);

        assertEquals(200, listed.getStatus());
        assertEquals("no-cache", listed.getHeaderString("Cache-Control"));
        assertEquals(1, page.get("totalItems").asInt());
        assertEquals("ui.title", page.at("/items/0/key").asText());
        String listEtag = listed.getHeaderString("ETag");
        assertNotNull(listEtag);

        Response notModified =
                service.listMessages(
                        request(
                                listQuery,
                                Map.of(HttpHeaders.IF_NONE_MATCH, "\"other\", " + listEtag)));
        assertEquals(304, notModified.getStatus());
        assertEquals("no-cache", notModified.getHeaderString("Cache-Control"));

        Response polish = service.messageBundle(request(Map.of("lang", List.of("pl"))));
        JsonNode polishBody = jsonBody(polish);
        assertEquals("pl", polish.getHeaderString("Content-Language"));
        assertEquals("pl", polishBody.get("language").asText());
        assertEquals("Tytuł", polishBody.at("/messages/ui.title").asText());
        assertEquals("English fallback", polishBody.at("/messages/ui.only-en").asText());

        Response qualityOrdered =
                service.messageBundle(
                        request(
                                Map.of("lang", List.of("zz")),
                                Map.of(
                                        HttpHeaders.ACCEPT_LANGUAGE,
                                        "de-DE;q=1, pl-PL;q=0.8, en;q=0.5")));
        assertEquals("pl", qualityOrdered.getHeaderString("Content-Language"));
        assertEquals("Tytuł", jsonBody(qualityOrdered).at("/messages/ui.title").asText());

        Response english =
                service.messageBundle(
                        request(Map.of(), Map.of(HttpHeaders.ACCEPT_LANGUAGE, "de-DE, *;q=0.9")));
        assertEquals("en", english.getHeaderString("Content-Language"));
    }

    @Test
    void adminCrudChangesEtagsAndWritesImmutableAuditSnapshots() throws Exception {
        seedMessages();
        MessageService admin = service("admin", "admin", "moderator");
        MessageInput createdInput =
                new MessageInput("ui.created", "en", "Created text", "Created description");

        Response createdResponse = admin.createMessage(createdInput);
        MessageResponse created =
                assertInstanceOf(MessageResponse.class, createdResponse.getEntity());

        assertEquals(201, createdResponse.getStatus());
        assertEquals("/api/v1/messages/" + created.id(), createdResponse.getLocation().toString());
        assertEquals(
                "message.created",
                scalarString(
                        dataSource,
                        "select action from audit_entries where target_id = ?",
                        created.id().toString()));
        assertEquals(
                "Created text",
                scalarString(
                        dataSource,
                        "select detail->>'text' from audit_entries where target_id = ?",
                        created.id().toString()));

        StackverseProblem duplicate =
                assertThrows(StackverseProblem.class, () -> admin.createMessage(createdInput));
        assertEquals(409, duplicate.status);
        assertTrue(duplicate.detail.contains("ui.created"));
        assertEquals(
                1,
                scalarLong(
                        dataSource,
                        "select count(*) from messages where key = 'ui.created' and language = 'en'"));

        Response before = admin.getMessage(request(), created.id().toString());
        String beforeEtag = before.getHeaderString("ETag");
        Response updatedResponse =
                admin.updateMessage(
                        created.id().toString(),
                        new MessageInput("ui.created", "en", "Updated text", null));
        assertEquals(200, updatedResponse.getStatus());
        Response after = admin.getMessage(request(), created.id().toString());
        assertNotEquals(beforeEtag, after.getHeaderString("ETag"));
        assertEquals("Updated text", jsonBody(after).get("text").asText());
        assertEquals(
                2,
                scalarLong(
                        dataSource,
                        "select count(*) from audit_entries where target_id = ?",
                        created.id().toString()));

        insertMessage(
                dataSource,
                UUID.fromString("aaaaaaaa-4444-4444-4444-444444444444"),
                "ui.duplicate",
                "en",
                "Duplicate",
                null,
                BASE_TIME);
        StackverseProblem duplicateUpdate =
                assertThrows(
                        StackverseProblem.class,
                        () ->
                                admin.updateMessage(
                                        created.id().toString(),
                                        new MessageInput("ui.duplicate", "en", "Collision", null)));
        assertEquals(409, duplicateUpdate.status);

        assertEquals(204, admin.deleteMessage(created.id().toString()).getStatus());
        assertEquals(
                "message.deleted",
                scalarString(
                        dataSource,
                        "select action from audit_entries where target_id = ? order by created_at desc limit 1",
                        created.id().toString()));
        StackverseProblem missing =
                assertThrows(
                        StackverseProblem.class,
                        () -> admin.getMessage(request(), created.id().toString()));
        assertEquals(404, missing.status);
    }

    @Test
    void messageWritesRequireAdminAndInvalidIdentifiersStayMasked() {
        MessageInput input = new MessageInput("ui.denied", "en", "Denied", null);

        StackverseProblem anonymous =
                assertThrows(StackverseProblem.class, () -> service(null).createMessage(input));
        assertEquals(401, anonymous.status);

        StackverseProblem reader =
                assertThrows(StackverseProblem.class, () -> service("reader").createMessage(input));
        assertEquals(403, reader.status);

        StackverseProblem invalidId =
                assertThrows(
                        StackverseProblem.class,
                        () -> service(null).getMessage(request(), "not-a-uuid"));
        assertEquals(404, invalidId.status);
    }

    private void seedMessages() throws SQLException {
        insertMessage(dataSource, TITLE_EN, "ui.title", "en", "Title", "Page title", BASE_TIME);
        insertMessage(dataSource, TITLE_PL, "ui.title", "pl", "Tytuł", "Tytuł strony", BASE_TIME);
        insertMessage(dataSource, ONLY_EN, "ui.only-en", "en", "English fallback", null, BASE_TIME);
    }

    private MessageService service(String username, String... roles) {
        return new MessageService(
                new DatabaseOperations(dataSource),
                authorization(username, roles),
                new RequestParameters(),
                new HttpResponses(mapper),
                new AuditTrail(mapper),
                new Localizer(dataSource));
    }

    private JsonNode jsonBody(Response response) throws Exception {
        return mapper.readTree(assertInstanceOf(String.class, response.getEntity()));
    }
}
