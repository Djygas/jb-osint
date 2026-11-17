package ru.jbosint.osint.profile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.data.SecureStore

/**
 * Оффлайн-кэш профилей.
 *
 * Профиль целиком (включая сообщения) сериализуется в JSON и кладётся в
 * [SecureStore] — блоб шифруется AES-256/GCM, ключ живёт в Android Keystore.
 * Индекс id лежит в обычных SharedPreferences: там нет персональных данных.
 * При повторном открытии профиль читается из кэша мгновенно, без сети.
 */
class ProfileStore(context: Context) {

    private val secure = SecureStore(context.applicationContext)
    private val prefs = context.getSharedPreferences("jb_profile_index", Context.MODE_PRIVATE)

    fun save(profile: ProfileData) {
        secure.put("profile_${profile.id}", ProfileJson.encode(profile))
        val order = ids().toMutableList()
        order.remove(profile.id)
        order.add(0, profile.id)
        while (order.size > MAX_PROFILES) {
            val dropped = order.removeAt(order.size - 1)
            secure.remove("profile_$dropped")
        }
        prefs.edit().putString("order", order.joinToString(",")).apply()
    }

    fun load(id: String): ProfileData? =
        secure.get("profile_$id")?.let { ProfileJson.decode(it) }

    fun ids(): List<String> =
        prefs.getString("order", "").orEmpty().split(',').filter { it.isNotBlank() }

    /** Краткие карточки для списка «Мои профили» — без тяжёлых блоков. */
    fun brief(): List<ProfileBrief> = ids().mapNotNull { id ->
        load(id)?.let { profile ->
            ProfileBrief(
                id = id,
                title = profile.subject.title,
                subtitle = profile.subject.subtitle,
                status = profile.subject.status,
                messages = profile.summary.messages,
                contacts = profile.summary.contacts,
                completeness = profile.completeness,
                builtAt = profile.builtAt,
                demo = profile.demo
            )
        }
    }

    fun delete(id: String) {
        secure.remove("profile_$id")
        val order = ids().filter { it != id }
        prefs.edit().putString("order", order.joinToString(",")).apply()
    }

    /** «Очистить профиль» — удаляет и кэш, и связанные выгрузки сообщений. */
    fun clear() {
        ids().forEach { secure.remove("profile_$it") }
        prefs.edit().remove("order").apply()
    }

    companion object {
        private const val MAX_PROFILES = 20
    }
}

/** Строка списка профилей. */
data class ProfileBrief(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: ProfileStatus,
    val messages: Int,
    val contacts: Int,
    val completeness: Int,
    val builtAt: Long,
    val demo: Boolean
)

/** JSON-сериализация профиля для зашифрованного кэша. */
object ProfileJson {

    fun encode(p: ProfileData): String {
        val root = JSONObject()
        root.put("id", p.id)
        root.put("query", p.query)
        root.put("builtAt", p.builtAt)
        root.put("demo", p.demo)
        root.put("subject", JSONObject().apply {
            put("title", p.subject.title)
            put("username", p.subject.username)
            put("id", p.subject.id)
            put("link", p.subject.link)
            put("avatar", p.subject.avatar)
            put("status", p.subject.status.name)
            put("platform", p.subject.platform)
            put("premium", p.subject.premium)
            put("bot", p.subject.bot)
            put("subtitle", p.subject.subtitle)
        })
        root.put("summary", JSONObject().apply {
            put("messages", p.summary.messages)
            put("posts", p.summary.posts)
            put("comments", p.summary.comments)
            put("replies", p.summary.replies)
            put("media", p.summary.media)
            put("from", p.summary.from)
            put("to", p.summary.to)
            put("geo", p.summary.geo)
            put("score", p.summary.score)
            put("contacts", p.summary.contacts)
            put("groups", p.summary.groups)
        })
        root.put("mix", JSONObject().apply {
            put("replies", p.mix.replies)
            put("media", p.mix.media)
            put("rounds", p.mix.rounds)
            put("voice", p.mix.voice)
            put("text", p.mix.text)
            put("links", p.mix.links)
        })
        root.put("identifiers", JSONArray().apply {
            p.identifiers.forEach { id ->
                put(JSONObject().apply {
                    put("kind", id.kind)
                    put("value", id.value)
                    put("platform", id.platform)
                    put("evidence", id.evidence)
                    put("confidence", id.confidence.name)
                })
            }
        })
        root.put("names", JSONArray().apply {
            p.names.forEach { put(JSONObject().put("date", it.date).put("name", it.name)) }
        })
        root.put("contacts", JSONArray().apply {
            p.contacts.forEach { c ->
                put(JSONObject().apply {
                    put("name", c.name)
                    put("username", c.username)
                    put("id", c.id)
                    put("replies", c.replies)
                    put("messages", c.messages)
                    put("lastAt", c.lastAt)
                    put("bot", c.bot)
                    put("link", c.link)
                    put("groups", JSONArray(c.commonGroups))
                })
            }
        })
        root.put("nodes", JSONArray().apply {
            p.nodes.forEach { n ->
                put(JSONObject().put("id", n.id).put("label", n.label).put("weight", n.weight)
                    .put("center", n.center).put("bot", n.bot))
            }
        })
        root.put("edges", JSONArray().apply {
            p.edges.forEach { e ->
                put(JSONObject().put("from", e.from).put("to", e.to)
                    .put("weight", e.weight).put("relation", e.relation))
            }
        })
        root.put("groups", JSONArray(p.groups))
        root.put("behavior", JSONObject().apply {
            put("hasData", p.behavior.hasData)
            put("heat", JSONArray().apply {
                p.behavior.heatmap.cells.forEach { row -> put(JSONArray(row.toList())) }
            })
            put("max", p.behavior.heatmap.max)
            put("night", p.behavior.heatmap.nightShare)
            put("peak", p.behavior.heatmap.peakHour)
            put("gap", p.behavior.heatmap.avgGapMinutes)
            put("pause", p.behavior.heatmap.longestPauseHours)
            put("sentiment", JSONObject().apply {
                put("pos", p.behavior.sentiment.positive)
                put("neg", p.behavior.sentiment.negative)
                put("neu", p.behavior.sentiment.neutral)
            })
            put("topics", JSONArray().apply {
                p.behavior.topics.forEach {
                    put(JSONObject().put("name", it.name).put("count", it.count).put("share", it.share))
                }
            })
        })
        root.put("content", JSONObject().apply {
            put("hasData", p.content.hasData)
            put("words", JSONArray().apply {
                p.content.words.forEach {
                    put(JSONObject().put("w", it.word).put("c", it.count).put("s", it.share))
                }
            })
            put("links", JSONArray().apply {
                p.content.links.forEach {
                    put(JSONObject().put("url", it.url).put("domain", it.domain)
                        .put("count", it.count).put("title", it.title))
                }
            })
            put("media", JSONArray().apply {
                p.content.media.forEach {
                    put(JSONObject().put("type", it.type.name).put("count", it.count).put("note", it.note))
                }
            })
            put("exif", JSONArray(p.content.exif))
            put("transcription", p.content.transcription)
        })
        root.put("external", JSONArray().apply {
            p.external.forEach {
                put(JSONObject().put("platform", it.platform).put("handle", it.handle)
                    .put("url", it.url).put("found", it.found).put("source", it.source))
            }
        })
        root.put("reputation", JSONObject().apply {
            put("toxicity", p.reputation.toxicity)
            put("spam", p.reputation.spam)
            put("trust", p.reputation.trust)
            put("notes", JSONArray(p.reputation.notes))
        })
        root.put("messages", JSONArray().apply {
            p.messages.forEach { m ->
                put(JSONObject().put("at", m.at).put("author", m.author).put("text", m.text)
                    .put("type", m.type.name).put("replyTo", m.replyTo).put("url", m.url))
            }
        })
        root.put("sources", JSONArray(p.sources))
        root.put("missing", JSONArray(p.missing))
        return root.toString()
    }

    fun decode(raw: String): ProfileData? = runCatching {
        val root = JSONObject(raw)
        val subject = root.getJSONObject("subject")
        val summary = root.getJSONObject("summary")
        val mix = root.optJSONObject("mix") ?: JSONObject()
        val behavior = root.optJSONObject("behavior") ?: JSONObject()
        val content = root.optJSONObject("content") ?: JSONObject()
        val reputation = root.optJSONObject("reputation") ?: JSONObject()

        val heat = behavior.optJSONArray("heat")
        val cells = Array(7) { day ->
            IntArray(24) { hour ->
                heat?.optJSONArray(day)?.optInt(hour, 0) ?: 0
            }
        }

        ProfileData(
            id = root.optString("id"),
            query = root.optString("query"),
            subject = ProfileSubject(
                title = subject.optString("title"),
                username = subject.optString("username"),
                id = subject.optString("id"),
                link = subject.optString("link"),
                avatar = subject.optString("avatar"),
                status = runCatching { ProfileStatus.valueOf(subject.optString("status")) }
                    .getOrDefault(ProfileStatus.UNKNOWN),
                platform = subject.optString("platform", "Telegram"),
                premium = subject.optBoolean("premium"),
                bot = subject.optBoolean("bot"),
                subtitle = subject.optString("subtitle")
            ),
            summary = ProfileSummary(
                messages = summary.optInt("messages"),
                posts = summary.optInt("posts"),
                comments = summary.optInt("comments"),
                replies = summary.optInt("replies"),
                media = summary.optInt("media"),
                from = summary.optLong("from"),
                to = summary.optLong("to"),
                geo = summary.optString("geo"),
                score = summary.optInt("score"),
                contacts = summary.optInt("contacts"),
                groups = summary.optInt("groups")
            ),
            mix = ContentMix(
                mix.optInt("replies"),
                mix.optInt("media"),
                mix.optInt("rounds"),
                mix.optInt("voice"),
                mix.optInt("text"),
                mix.optInt("links")
            ),
            identifiers = root.optJSONArray("identifiers").objects { obj ->
                ProfileIdentity(
                    kind = obj.optString("kind"),
                    value = obj.optString("value"),
                    platform = obj.optString("platform"),
                    evidence = obj.optString("evidence"),
                    confidence = runCatching { Confidence.valueOf(obj.optString("confidence")) }
                        .getOrDefault(Confidence.UNVERIFIED)
                )
            },
            names = root.optJSONArray("names").objects { obj ->
                NameHistoryItem(obj.optString("date"), obj.optString("name"))
            },
            contacts = root.optJSONArray("contacts").objects { obj ->
                ProfileContact(
                    name = obj.optString("name"),
                    username = obj.optString("username"),
                    id = obj.optString("id"),
                    replies = obj.optInt("replies"),
                    messages = obj.optInt("messages"),
                    lastAt = obj.optLong("lastAt"),
                    commonGroups = obj.optJSONArray("groups").strings(),
                    bot = obj.optBoolean("bot"),
                    link = obj.optString("link")
                )
            },
            nodes = root.optJSONArray("nodes").objects { obj ->
                GraphNode(
                    id = obj.optString("id"),
                    label = obj.optString("label"),
                    weight = obj.optInt("weight", 1),
                    center = obj.optBoolean("center"),
                    bot = obj.optBoolean("bot")
                )
            },
            edges = root.optJSONArray("edges").objects { obj ->
                GraphEdge(
                    from = obj.optString("from"),
                    to = obj.optString("to"),
                    weight = obj.optInt("weight", 1),
                    relation = obj.optString("relation", "общение")
                )
            },
            groups = root.optJSONArray("groups").strings(),
            behavior = BehaviorBlock(
                heatmap = Heatmap(
                    cells = cells,
                    max = behavior.optInt("max"),
                    nightShare = behavior.optInt("night"),
                    peakHour = behavior.optInt("peak", -1),
                    avgGapMinutes = behavior.optInt("gap"),
                    longestPauseHours = behavior.optInt("pause")
                ),
                sentiment = Sentiment(
                    positive = behavior.optJSONObject("sentiment")?.optInt("pos") ?: 0,
                    negative = behavior.optJSONObject("sentiment")?.optInt("neg") ?: 0,
                    neutral = behavior.optJSONObject("sentiment")?.optInt("neu") ?: 0
                ),
                topics = behavior.optJSONArray("topics").objects { obj ->
                    TopicSlice(obj.optString("name"), obj.optInt("count"), obj.optInt("share"))
                },
                hasData = behavior.optBoolean("hasData")
            ),
            content = ContentBlock(
                words = content.optJSONArray("words").objects { obj ->
                    WordWeight(obj.optString("w"), obj.optInt("c"), obj.optInt("s"))
                },
                links = content.optJSONArray("links").objects { obj ->
                    LinkRow(obj.optString("url"), obj.optString("domain"), obj.optInt("count"), obj.optString("title"))
                },
                media = content.optJSONArray("media").objects { obj ->
                    MediaRow(
                        MsgType.from(obj.optString("type")),
                        obj.optInt("count"),
                        obj.optString("note")
                    )
                },
                exif = content.optJSONArray("exif").strings(),
                transcription = content.optString("transcription"),
                hasData = content.optBoolean("hasData")
            ),
            external = root.optJSONArray("external").objects { obj ->
                ExternalHit(
                    platform = obj.optString("platform"),
                    handle = obj.optString("handle"),
                    url = obj.optString("url"),
                    found = obj.optBoolean("found"),
                    source = obj.optString("source")
                )
            },
            reputation = Reputation(
                toxicity = reputation.optInt("toxicity"),
                spam = reputation.optInt("spam"),
                trust = reputation.optInt("trust"),
                notes = reputation.optJSONArray("notes").strings()
            ),
            messages = root.optJSONArray("messages").objects { obj ->
                ProfileMessage(
                    at = obj.optLong("at"),
                    author = obj.optString("author"),
                    text = obj.optString("text"),
                    type = MsgType.from(obj.optString("type")),
                    replyTo = obj.optString("replyTo"),
                    url = obj.optString("url")
                )
            },
            sources = root.optJSONArray("sources").strings(),
            missing = root.optJSONArray("missing").strings(),
            builtAt = root.optLong("builtAt"),
            demo = root.optBoolean("demo")
        )
    }.getOrNull()

    private fun <T> JSONArray?.objects(map: (JSONObject) -> T): List<T> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(map)
        }
    }

    private fun JSONArray?.strings(): List<String> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }
}
