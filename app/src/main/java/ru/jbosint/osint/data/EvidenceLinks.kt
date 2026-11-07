package ru.jbosint.osint.data

import ru.jbosint.osint.core.report.SourceCatalog
import java.net.URLEncoder

/**
 * Ссылки-доказательства для сведений: адрес страницы, где значение можно увидеть своими глазами.
 * Ссылка попадает в досье (раздел 2.6 PDF) и в лист «Доказательства» XLSX.
 */
object EvidenceLinks {

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun url(moduleId: String, query: String, value: String): String {
        val id = moduleId.substringBefore('_').ifBlank { moduleId }
        val v = value.trim()
        return when (id) {
            "github" -> if (v.matches(Regex("^[A-Za-z0-9-]{1,39}$"))) "https://github.com/$v" else "https://github.com/search?q=${enc(query)}&type=users"
            "gitlab" -> if (v.matches(Regex("^[A-Za-z0-9_.-]{1,64}$"))) "https://gitlab.com/$v" else "https://gitlab.com/search?search=${enc(query)}"
            "hibp" -> "https://haveibeenpwned.com/account/${enc(query)}"
            "leakcheck" -> "https://leakcheck.io/"
            "virustotal" -> "https://www.virustotal.com/gui/search/${enc(query)}"
            "shodan" -> if (v.isIp()) "https://www.shodan.io/host/$v" else "https://www.shodan.io/search?query=${enc(query)}"
            "numverify" -> "https://numverify.com/"
            "hunter" -> "https://hunter.io/email-verifier/${enc(query)}"
            "ipinfo" -> if (v.isIp()) "https://ipinfo.io/$v" else "https://ipinfo.io/${enc(query)}"
            "tgstat" -> "https://tgstat.ru/channel/${enc(v.trimStart('@'))}"
            "vk" -> "https://vk.com/${enc(v.trimStart('@'))}"
            "dyxless" -> "https://t.me/${enc(v.trimStart('@'))}"
            "opencorporates" -> "https://opencorporates.com/companies?q=${enc(query)}&jurisdiction_code=ru"
            "google" -> "https://www.google.com/search?q=${enc(query)}"
            "yandex" -> "https://yandex.ru/search/?text=${enc(query)}"
            "brave" -> "https://search.brave.com/search?q=${enc(query)}"
            "ddg" -> "https://duckduckgo.com/?q=${enc(query)}"
            "wikipedia" -> "https://ru.wikipedia.org/wiki/${enc(v.replace(' ', '_'))}"
            "urlscan" -> if (v.matches(Regex("^[0-9a-f-]{36}$"))) "https://urlscan.io/result/$v/" else "https://urlscan.io/search/#${enc(query)}"
            "securitytrails" -> "https://securitytrails.com/domain/${enc(query)}/dns"
            "abuseipdb" -> if (v.isIp()) "https://www.abuseipdb.com/check/$v" else "https://www.abuseipdb.com/"
            "rdap" -> "https://rdap.org/domain/${enc(query)}"
            "face" -> ""
            else -> SourceCatalog.ref(moduleId).url
        }
    }

    private fun String.isIp(): Boolean =
        matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || contains(':') && matches(Regex("^[0-9a-fA-F:]{3,45}$"))
}
