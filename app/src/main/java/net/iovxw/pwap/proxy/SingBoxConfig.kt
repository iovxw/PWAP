package net.iovxw.pwap.proxy

import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfig {

    fun buildConfig(outboundJson: String, listenPort: Int, dnsServer: String = "https://dns.google/dns-query"): String {
        val config = JSONObject()

        // Log
        config.put("log", JSONObject().apply {
            put("level", "info")
            put("timestamp", true)
        })

        // DNS - resolve proxy server domain via direct connection
        // Supports: IP (8.8.8.8), DoH (https://), DoT (tls://), DoQ (quic://), DoH3 (h3://)
        config.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "default-dns")
                    put("address", dnsServer.ifBlank { "https://dns.google/dns-query" })
                    put("detour", "direct")
                })
            })
        })

        // Inbounds - mixed HTTP/SOCKS proxy
        config.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "mixed")
                put("tag", "mixed-in")
                put("listen", "127.0.0.1")
                put("listen_port", listenPort)
            })
        })

        // Outbounds - proxy + direct (direct needed for DNS resolution)
        config.put("outbounds", JSONArray().apply {
            put(JSONObject(outboundJson).apply {
                if (!has("tag")) put("tag", "proxy")
            })
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
        })

        return config.toString(2)
    }

    /**
     * Parse various proxy link formats into sing-box outbound JSON.
     * Supports: ss://, trojan://, vmess://, vless://, raw sing-box JSON
     */
    fun parseProxyLink(input: String): String? {
        val trimmed = input.trim()

        // Already JSON
        if (trimmed.startsWith("{")) {
            return try {
                val obj = JSONObject(trimmed)
                if (obj.has("type")) {
                    trimmed
                } else null
            } catch (_: Exception) { null }
        }

        return when {
            trimmed.startsWith("ss://") -> parseShadowsocks(trimmed)
            trimmed.startsWith("trojan-go://") -> parseTrojanGo(trimmed)
            trimmed.startsWith("trojan://") -> parseTrojan(trimmed)
            trimmed.startsWith("vmess://") -> parseVmess(trimmed)
            trimmed.startsWith("vless://") -> parseVless(trimmed)
            trimmed.startsWith("socks5://") || trimmed.startsWith("socks://") || trimmed.startsWith("socks4://") || trimmed.startsWith("socks4a://") -> parseSocks(trimmed)
            trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://") -> parseHysteria2(trimmed)
            trimmed.startsWith("hysteria://") -> parseHysteria1(trimmed)
            trimmed.startsWith("tuic://") -> parseTuic(trimmed)
            trimmed.startsWith("anytls://") -> parseAnytls(trimmed)
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> parseHttpProxy(trimmed)
            else -> null
        }
    }

    private fun parseSocks(link: String): String? {
        return try {
            // Parse as HTTP URL to handle user:pass@host:port format
            val httpUrl = java.net.URL(link.replaceFirst(Regex("^socks[45a]?://"), "http://"))
            val host = httpUrl.host
            val port = if (httpUrl.port > 0) httpUrl.port else 1080

            var username: String? = null
            var password: String? = null

            val userInfo = httpUrl.userInfo
            if (userInfo != null) {
                if (userInfo.contains(":")) {
                    val parts = userInfo.split(":", limit = 2)
                    username = parts[0]
                    password = parts[1]
                } else {
                    // v2rayN format: base64 encoded user:pass
                    try {
                        val decoded = String(
                            android.util.Base64.decode(userInfo, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING),
                            Charsets.UTF_8
                        )
                        val parts = decoded.split(":", limit = 2)
                        username = parts[0]
                        password = if (parts.size > 1) parts[1] else ""
                    } catch (_: Exception) {
                        username = userInfo
                    }
                }
            }

            JSONObject().apply {
                put("type", "socks")
                put("tag", "proxy")
                put("server", host)
                put("server_port", port)
                val version = when {
                    link.startsWith("socks4a://") -> "4a"
                    link.startsWith("socks4://") -> "4"
                    else -> "5"
                }
                put("version", version)
                if (!username.isNullOrBlank()) {
                    put("username", username)
                    put("password", password ?: "")
                }
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun parseShadowsocks(link: String): String? {
        return try {
            // ss://method:password@host:port#name
            // or ss://base64@host:port#name
            val uri = java.net.URI(link)
            val host = uri.host
            val port = uri.port

            val userInfo = if (uri.userInfo != null) {
                uri.userInfo
            } else {
                // ss://base64#name format
                val encoded = link.removePrefix("ss://").substringBefore("#").substringBefore("@")
                String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING), Charsets.UTF_8)
            }

            val parts = userInfo.split(":", limit = 2)
            if (parts.size != 2) return null

            val method = parts[0]
            val password = parts[1]

            JSONObject().apply {
                put("type", "shadowsocks")
                put("tag", "proxy")
                put("server", host)
                put("server_port", port)
                put("method", method)
                put("password", password)
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun parseTrojan(link: String): String? {
        return try {
            val uri = java.net.URI(link)
            val password = uri.userInfo ?: return null
            val host = uri.host
            val port = if (uri.port > 0) uri.port else 443

            val params = parseQueryParams(uri.rawQuery ?: "")
            val sni = params["sni"] ?: host

            JSONObject().apply {
                put("type", "trojan")
                put("tag", "proxy")
                put("server", host)
                put("server_port", port)
                put("password", password)
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", sni)
                    if (params["allowInsecure"] == "1") put("insecure", true)
                    params["fp"]?.takeIf { it.isNotBlank() }?.let { fp ->
                        put("utls", JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", fp)
                        })
                    }
                    params["alpn"]?.takeIf { it.isNotBlank() }?.let {
                        put("alpn", JSONArray().apply {
                            it.split(",").forEach { a -> put(a.trim()) }
                        })
                    }
                })
                params["type"]?.let { transport ->
                    if (transport != "tcp") {
                        put("transport", JSONObject().apply {
                            put("type", transport)
                            params["path"]?.let { put("path", it) }
                            params["host"]?.let { put("headers", JSONObject().put("Host", it)) }
                        })
                    }
                }
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun parseVmess(link: String): String? {
        return try {
            val encoded = link.removePrefix("vmess://")
            val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val json = JSONObject(decoded)

            val host = json.optString("add")
            val port = json.optString("port").toIntOrNull() ?: return null
            val uuid = json.optString("id")
            val aid = json.optString("aid", "0").toIntOrNull() ?: 0

            JSONObject().apply {
                put("type", "vmess")
                put("tag", "proxy")
                put("server", host)
                put("server_port", port)
                put("uuid", uuid)
                put("alter_id", aid)
                put("security", json.optString("scy", "auto"))

                val tls = json.optString("tls")
                if (tls == "tls") {
                    put("tls", JSONObject().apply {
                        put("enabled", true)
                        put("server_name", json.optString("sni", host))
                        val fp = json.optString("fp", "")
                        if (fp.isNotEmpty()) {
                            put("utls", JSONObject().apply {
                                put("enabled", true)
                                put("fingerprint", fp)
                            })
                        }
                        val alpn = json.optString("alpn", "")
                        if (alpn.isNotEmpty()) {
                            put("alpn", JSONArray().apply {
                                alpn.split(",").forEach { a -> put(a.trim()) }
                            })
                        }
                    })
                }

                val net = json.optString("net", "tcp")
                if (net != "tcp") {
                    put("transport", JSONObject().apply {
                        put("type", net)
                        json.optString("path").takeIf { it.isNotEmpty() }?.let { put("path", it) }
                        json.optString("host").takeIf { it.isNotEmpty() }?.let {
                            put("headers", JSONObject().put("Host", it))
                        }
                    })
                }
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun parseVless(link: String): String? {
        return try {
            val uri = java.net.URI(link)
            val uuid = uri.userInfo ?: return null
            val host = uri.host
            val port = if (uri.port > 0) uri.port else 443

            val params = parseQueryParams(uri.rawQuery ?: "")

            JSONObject().apply {
                put("type", "vless")
                put("tag", "proxy")
                put("server", host)
                put("server_port", port)
                put("uuid", uuid)
                params["flow"]?.let { put("flow", it) }

                val security = params["security"] ?: ""
                if (security == "tls" || security == "reality") {
                    put("tls", JSONObject().apply {
                        put("enabled", true)
                        put("server_name", params["sni"] ?: host)
                        // uTLS fingerprint: required for reality, optional for tls
                        val fp = params["fp"] ?: if (security == "reality") "chrome" else ""
                        if (fp.isNotEmpty()) {
                            put("utls", JSONObject().apply {
                                put("enabled", true)
                                put("fingerprint", fp)
                            })
                        }
                        params["alpn"]?.takeIf { it.isNotBlank() }?.let {
                            put("alpn", JSONArray().apply {
                                it.split(",").forEach { a -> put(a.trim()) }
                            })
                        }
                        if (security == "reality") {
                            put("reality", JSONObject().apply {
                                put("enabled", true)
                                params["pbk"]?.let { put("public_key", it) }
                                params["sid"]?.let { put("short_id", it) }
                            })
                        }
                    })
                }

                val transport = params["type"] ?: "tcp"
                if (transport != "tcp") {
                    put("transport", JSONObject().apply {
                        put("type", transport)
                        params["path"]?.let { put("path", it) }
                        params["host"]?.let { put("headers", JSONObject().put("Host", it)) }
                        params["serviceName"]?.let { put("service_name", it) }
                    })
                }
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun parseHysteria2(link: String): String? {
        return try {
            val httpUrl = java.net.URL(link.replaceFirst(Regex("^(hysteria2|hy2)://"), "http://"))
            val host = httpUrl.host
            val port = if (httpUrl.port > 0) httpUrl.port else 443
            val password = httpUrl.userInfo ?: ""
            val params = parseQueryParams(httpUrl.query ?: "")

            JSONObject().apply {
                put("type", "hysteria2")
                put("tag", "proxy")
                put("server", host)
                put("server_port", port)
                put("password", password)
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", params["sni"] ?: host)
                    if (params["insecure"] == "1" || params["insecure"] == "true") {
                        put("insecure", true)
                    }
                })
                params["obfs-password"]?.takeIf { it.isNotBlank() }?.let { obfsPass ->
                    put("obfs", JSONObject().apply {
                        put("type", "salamander")
                        put("password", obfsPass)
                    })
                }
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun parseHysteria1(link: String): String? {
        return try {
            val httpUrl = java.net.URL(link.replace("hysteria://", "http://"))
            val host = httpUrl.host
            val port = if (httpUrl.port > 0) httpUrl.port else 443
            val params = parseQueryParams(httpUrl.query ?: "")

            JSONObject().apply {
                put("type", "hysteria")
                put("tag", "proxy")
                put("server", host)
                put("server_port", port)
                params["auth"]?.takeIf { it.isNotBlank() }?.let {
                    put("auth_str", it)
                }
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", params["peer"] ?: host)
                    if (params["insecure"] == "1" || params["insecure"] == "true") {
                        put("insecure", true)
                    }
                    params["alpn"]?.takeIf { it.isNotBlank() }?.let {
                        put("alpn", JSONArray().apply {
                            it.split(",").forEach { a -> put(a.trim()) }
                        })
                    }
                })
                params["upmbps"]?.toIntOrNull()?.let { put("up_mbps", it) }
                params["downmbps"]?.toIntOrNull()?.let { put("down_mbps", it) }
                params["obfsParam"]?.takeIf { it.isNotBlank() }?.let {
                    put("obfs", it)
                }
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun parseTuic(link: String): String? {
        return try {
            val httpUrl = java.net.URL(link.replace("tuic://", "http://"))
            val host = httpUrl.host
            val port = if (httpUrl.port > 0) httpUrl.port else 443
            val params = parseQueryParams(httpUrl.query ?: "")

            var uuid = ""
            var password = ""
            val userInfo = httpUrl.userInfo
            if (userInfo != null) {
                if (userInfo.contains(":")) {
                    val parts = userInfo.split(":", limit = 2)
                    uuid = parts[0]
                    password = parts[1]
                } else {
                    uuid = userInfo
                }
            }

            JSONObject().apply {
                put("type", "tuic")
                put("tag", "proxy")
                put("server", host)
                put("server_port", port)
                put("uuid", uuid)
                put("password", password)
                params["congestion_control"]?.let { put("congestion_control", it) }
                params["udp_relay_mode"]?.let { put("udp_relay_mode", it) }
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", params["sni"] ?: host)
                    if (params["allow_insecure"] == "1") put("insecure", true)
                    params["alpn"]?.takeIf { it.isNotBlank() }?.let {
                        put("alpn", JSONArray().apply {
                            it.split(",").forEach { a -> put(a.trim()) }
                        })
                    }
                })
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun parseAnytls(link: String): String? {
        return try {
            val httpUrl = java.net.URL(link.replace("anytls://", "http://"))
            val host = httpUrl.host
            val port = if (httpUrl.port > 0) httpUrl.port else 443
            val password = httpUrl.userInfo ?: ""
            val params = parseQueryParams(httpUrl.query ?: "")

            JSONObject().apply {
                put("type", "anytls")
                put("tag", "proxy")
                put("server", host)
                put("server_port", port)
                put("password", password)
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", params["sni"] ?: host)
                    if (params["insecure"] == "1" || params["insecure"] == "true") {
                        put("insecure", true)
                    }
                })
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun parseTrojanGo(link: String): String? {
        return try {
            val httpUrl = java.net.URL(link.replace("trojan-go://", "http://"))
            val host = httpUrl.host
            val port = if (httpUrl.port > 0) httpUrl.port else 443
            val password = httpUrl.userInfo ?: ""
            val params = parseQueryParams(httpUrl.query ?: "")

            JSONObject().apply {
                put("type", "trojan")
                put("tag", "proxy")
                put("server", host)
                put("server_port", port)
                put("password", password)
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", params["sni"] ?: host)
                })
                val transport = params["type"] ?: "tcp"
                if (transport != "tcp") {
                    put("transport", JSONObject().apply {
                        put("type", transport)
                        params["path"]?.let { put("path", it) }
                        params["host"]?.let { put("headers", JSONObject().put("Host", it)) }
                    })
                }
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun parseHttpProxy(link: String): String? {
        return try {
            val httpUrl = java.net.URL(link)
            if (httpUrl.path.isNotBlank() && httpUrl.path != "/") return null
            val host = httpUrl.host
            val port = if (httpUrl.port > 0) httpUrl.port else if (httpUrl.protocol == "https") 443 else 80

            JSONObject().apply {
                put("type", "http")
                put("tag", "proxy")
                put("server", host)
                put("server_port", port)
                httpUrl.userInfo?.let { userInfo ->
                    if (userInfo.contains(":")) {
                        val parts = userInfo.split(":", limit = 2)
                        put("username", parts[0])
                        put("password", parts[1])
                    } else {
                        put("username", userInfo)
                    }
                }
                if (httpUrl.protocol == "https") {
                    put("tls", JSONObject().apply {
                        put("enabled", true)
                        put("server_name", host)
                    })
                }
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").associate {
            val parts = it.split("=", limit = 2)
            val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
            val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
            key to value
        }
    }
}
