package dev.stackverse.gateway

import dev.stackverse.gateway.config.GatewayProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class GatewayPropertiesTest {

    @Test
    fun `blank optional urls are treated as absent and issuer bases are normalized`() {
        val gateway = gateway(
            frontendUrl = "  ",
            issuerUri = "http://localhost:8180/realms/stackverse/",
            internalIssuerUri = "",
        )

        assertNull(gateway.frontendUrl)
        assertEquals("http://localhost:8180/realms/stackverse", gateway.oidc.issuerUri)
        assertEquals(gateway.oidc.issuerUri, gateway.oidc.internalIssuerUri)
    }

    @Test
    fun `configured frontend and internal issuer are kept as uris without trailing slashes`() {
        val gateway = gateway(
            frontendUrl = "http://localhost:5173",
            issuerUri = "http://localhost:8180/realms/stackverse/",
            internalIssuerUri = "http://keycloak:8080/realms/stackverse/",
        )

        assertEquals(URI("http://localhost:5173"), gateway.frontendUrl)
        assertEquals("http://localhost:8180/realms/stackverse", gateway.oidc.issuerUri)
        assertEquals("http://keycloak:8080/realms/stackverse", gateway.oidc.internalIssuerUri)
    }

    @Test
    fun `cookies are secure only when public url is https`() {
        assertFalse(gateway(publicUrl = "http://localhost:8000").cookiesSecure)
        assertTrue(gateway(publicUrl = "https://stackverse.example").cookiesSecure)
    }

    private fun gateway(
        publicUrl: String = "http://localhost:8000",
        frontendUrl: String? = null,
        issuerUri: String = "http://localhost:8180/realms/stackverse",
        internalIssuerUri: String? = null,
    ) = GatewayProperties(
        backendUrl = URI("http://localhost:8080"),
        frontendUrl = frontendUrl,
        publicUrl = URI(publicUrl),
        oidc = GatewayProperties.Oidc(
            issuerUri = issuerUri,
            internalIssuerUri = internalIssuerUri,
            clientId = "stackverse-gateway",
            clientSecret = "stackverse-secret",
        ),
    )
}
