package dev.stackverse.backend

import dev.stackverse.backend.message.MessageRepository
import dev.stackverse.backend.message.MessageSeeder
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

class MessageApiTest : IntegrationTest() {

    @Autowired
    lateinit var messageRepository: MessageRepository

    @Autowired
    lateinit var messageSeeder: MessageSeeder

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private fun uniqueKey() = "test.${UUID.randomUUID().toString().replace("-", "")}"

    private fun createMessage(key: String, language: String, text: String): String {
        val body = mockMvc.perform(
            post("/api/v1/messages").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"key":"$key","language":"$language","text":"$text"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", containsString("/api/v1/messages/")))
            .andReturn().response.contentAsString
        return objectMapper.readTree(body).get("id").asString()
    }

    @Test
    fun `seed is imported and idempotent, preserving runtime edits`() {
        val seeded = messageRepository.findByKeyAndLanguage("ui.action.save", "en")!!
        assert(seeded.text == "Save")

        val edited = messageRepository.findByKeyAndLanguage("ui.action.cancel", "en")!!
        val id = edited.id
        mockMvc.perform(
            put("/api/v1/messages/{id}", id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"key":"ui.action.cancel","language":"en","text":"Nevermind"}"""),
        ).andExpect(status().isOk)

        val countBefore = messageRepository.count()
        messageSeeder.run(DefaultApplicationArguments())

        assert(messageRepository.count() == countBefore) { "re-seeding must not insert duplicates" }
        assert(messageRepository.findByKeyAndLanguage("ui.action.cancel", "en")!!.text == "Nevermind") {
            "re-seeding must not overwrite runtime edits"
        }
    }

    @Test
    fun `reads are public with etag revalidation, writes change the etag`() {
        val key = uniqueKey()
        createMessage(key, "en", "hello")

        val result = mockMvc.perform(get("/api/v1/messages").param("key", key))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(header().exists("ETag"))
            .andExpect(jsonPath("$.items[0].text").value("hello"))
            .andReturn()
        val etag = result.response.getHeader("ETag")!!

        mockMvc.perform(get("/api/v1/messages").param("key", key).header("If-None-Match", etag))
            .andExpect(status().isNotModified)

        val id = messageRepository.findByKeyAndLanguage(key, "en")!!.id
        mockMvc.perform(
            put("/api/v1/messages/{id}", id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"key":"$key","language":"en","text":"changed"}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(get("/api/v1/messages").param("key", key).header("If-None-Match", etag))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].text").value("changed"))
    }

    @Test
    fun `q filters by case-insensitive substring over key and text`() {
        val key = uniqueKey()
        val marker = UUID.randomUUID().toString().replace("-", "")
        createMessage(key, "en", "text with $marker inside")

        // substring of the key, matched case-insensitively
        mockMvc.perform(get("/api/v1/messages").param("q", key.drop(5).take(12).uppercase()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.items[0].key").value(key))

        // substring of the text
        mockMvc.perform(get("/api/v1/messages").param("q", marker.take(16).uppercase()))
            .andExpect(jsonPath("$.totalItems").value(1))

        // LIKE wildcards in the query are literals, not patterns
        mockMvc.perform(get("/api/v1/messages").param("q", "%$marker%"))
            .andExpect(jsonPath("$.totalItems").value(0))

        mockMvc.perform(get("/api/v1/messages").param("q", "x".repeat(201)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `single message read supports etag and 404`() {
        val id = createMessage(uniqueKey(), "en", "one")
        val etag = mockMvc.perform(get("/api/v1/messages/{id}", id))
            .andExpect(status().isOk)
            .andReturn().response.getHeader("ETag")!!
        mockMvc.perform(get("/api/v1/messages/{id}", id).header("If-None-Match", etag))
            .andExpect(status().isNotModified)
        mockMvc.perform(get("/api/v1/messages/{id}", UUID.randomUUID())).andExpect(status().isNotFound)
    }

    @Test
    fun `writes require the admin role`() {
        val body = """{"key":"${uniqueKey()}","language":"en","text":"x"}"""
        mockMvc.perform(post("/api/v1/messages").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/v1/messages").with(user("alice")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/api/v1/messages").with(moderator()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `duplicate key and language pair is a conflict`() {
        val key = uniqueKey()
        createMessage(key, "en", "first")
        mockMvc.perform(
            post("/api/v1/messages").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"key":"$key","language":"en","text":"second"}"""),
        ).andExpect(status().isConflict)
        // same key in another language is fine
        createMessage(key, "pl", "drugi")
    }

    @Test
    fun `invalid input lists field errors`() {
        mockMvc.perform(
            post("/api/v1/messages").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"key":"Not.A.Valid.KEY","language":"english","text":""}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[?(@.field=='key')].messageKey").value("validation.message.key.invalid"))
            .andExpect(jsonPath("$.errors[?(@.field=='language')].messageKey").value("validation.message.language.invalid"))
            .andExpect(jsonPath("$.errors[?(@.field=='text')].messageKey").value("validation.message.text.required"))
    }

    @Test
    fun `delete removes the message`() {
        val id = createMessage(uniqueKey(), "en", "gone soon")
        mockMvc.perform(delete("/api/v1/messages/{id}", id).with(admin())).andExpect(status().isNoContent)
        mockMvc.perform(get("/api/v1/messages/{id}", id)).andExpect(status().isNotFound)
    }

    @Test
    fun `bundle resolves language and falls back to english per key`() {
        val enOnly = uniqueKey()
        createMessage(enOnly, "en", "english only")

        // lang parameter wins over Accept-Language; missing keys fall back to en
        mockMvc.perform(get("/api/v1/messages/bundle").param("lang", "pl").header("Accept-Language", "en"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Language", "pl"))
            .andExpect(jsonPath("$.language").value("pl"))
            .andExpect(jsonPath("$.messages['ui.action.save']").value("Zapisz"))
            .andExpect(jsonPath("$.messages['$enOnly']").value("english only"))

        // Accept-Language quality ordering picks the first supported language
        mockMvc.perform(get("/api/v1/messages/bundle").header("Accept-Language", "fr;q=1.0, pl;q=0.9, en;q=0.5"))
            .andExpect(header().string("Content-Language", "pl"))

        // unsupported values fall back to en, never error
        mockMvc.perform(get("/api/v1/messages/bundle").param("lang", "xx").header("Accept-Language", "de"))
            .andExpect(header().string("Content-Language", "en"))
            .andExpect(jsonPath("$.messages['ui.action.save']").value("Save"))
    }
}
