package com.mnirog.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

object JwtService {
    private val secret = System.getenv("JWT_SECRET") ?: "mnirog-dev-secret-change-in-production"
    private const val ISSUER = "mnirog-api"
    private const val AUDIENCE = "mnirog-clients"
    private val algorithm = Algorithm.HMAC256(secret)

    val verifier: JWTVerifier = JWT
        .require(algorithm)
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .build()

    fun generateAccessToken(userId: String, phone: String, role: String): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(userId)
            .withClaim("phone", phone)
            .withClaim("role", role)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + 3_600_000L))
            .sign(algorithm)
    }

    fun generateRefreshToken(userId: String): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withSubject(userId)
            .withClaim("type", "refresh")
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + 30L * 86_400_000L))
            .sign(algorithm)
    }
}
