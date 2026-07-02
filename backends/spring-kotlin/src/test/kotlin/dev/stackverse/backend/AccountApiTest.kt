package dev.stackverse.backend

import dev.stackverse.backend.account.UserAccountRepository
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class AccountApiTest : IntegrationTest() {

    @Autowired
    lateinit var userAccountRepository: UserAccountRepository

    private fun uniqueUser() = "user-${UUID.randomUUID().toString().take(8)}"

    @Test
    fun `me echoes identity and application roles from the token`() {
        mockMvc.perform(get("/api/v1/me").with(user("demo")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("demo"))
            .andExpect(jsonPath("$.name").value("Demo User"))
            .andExpect(jsonPath("$.email").value("demo@stackverse.local"))
            .andExpect(jsonPath("$.roles", hasSize<Any>(0)))

        // Keycloak plumbing roles are filtered out; admin carries the composite pair
        mockMvc.perform(get("/api/v1/me").with(admin()))
            .andExpect(jsonPath("$.roles", containsInAnyOrder("admin", "moderator")))

        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `accounts are provisioned lazily on any authenticated request`() {
        val username = uniqueUser()
        assert(userAccountRepository.findById(username).isEmpty)

        mockMvc.perform(get("/api/v1/me").with(user(username))).andExpect(status().isOk)

        val account = userAccountRepository.findById(username).orElseThrow()
        assert(account.firstSeen == account.lastSeen)

        mockMvc.perform(get("/api/v1/me").with(user(username))).andExpect(status().isOk)
        val updated = userAccountRepository.findById(username).orElseThrow()
        assert(!updated.lastSeen.isBefore(account.lastSeen))
    }

    @Test
    fun `blocked users get 403 on authenticated calls until unblocked`() {
        val username = uniqueUser()
        mockMvc.perform(get("/api/v1/me").with(user(username))).andExpect(status().isOk)

        // blocking needs a reason
        mockMvc.perform(
            put("/api/v1/admin/users/{u}/status", username).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"blocked"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].messageKey").value("validation.block.reason.required"))

        mockMvc.perform(
            put("/api/v1/admin/users/{u}/status", username).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"blocked","reason":"abuse"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("blocked"))
            .andExpect(jsonPath("$.blockedReason").value("abuse"))

        // localized problem document on every authenticated endpoint (rule 17)
        mockMvc.perform(get("/api/v1/me").with(user(username)).header("Accept-Language", "pl"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("Twoje konto zostało zablokowane."))

        // the anonymous public surface keeps working
        mockMvc.perform(get("/api/v1/messages/bundle")).andExpect(status().isOk)

        mockMvc.perform(
            put("/api/v1/admin/users/{u}/status", username).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"active"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.blockedReason").doesNotExist())

        mockMvc.perform(get("/api/v1/me").with(user(username))).andExpect(status().isOk)
    }

    @Test
    fun `admins cannot block themselves`() {
        mockMvc.perform(get("/api/v1/me").with(admin())).andExpect(status().isOk)
        mockMvc.perform(
            put("/api/v1/admin/users/{u}/status", "admin").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"blocked","reason":"oops"}"""),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `user directory is admin-only with search and status filter`() {
        val username = uniqueUser()
        mockMvc.perform(get("/api/v1/me").with(user(username))).andExpect(status().isOk)

        mockMvc.perform(get("/api/v1/admin/users").with(moderator())).andExpect(status().isForbidden)

        mockMvc.perform(get("/api/v1/admin/users").param("q", username.take(12)).with(admin()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].username").value(username))
            .andExpect(jsonPath("$.items[0].bookmarkCount").value(0))

        mockMvc.perform(get("/api/v1/admin/users/{u}", username).with(admin()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value(username))

        mockMvc.perform(get("/api/v1/admin/users/{u}", "never-seen-${UUID.randomUUID()}").with(admin()))
            .andExpect(status().isNotFound)
    }
}
