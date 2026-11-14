package com.cue.offlinebrain.summary

/** Document category inferred only from textual structure and conservative keyword evidence. */
enum class DocumentKind { RESUME, ARTICLE, REPORT, FORM, UNKNOWN }

data class ResumeJob(val role: String, val company: String, val place: String, val dates: String, val bullets: List<String>) {
    fun evidence(): String = (listOf("$role | $company | $place | $dates") + bullets).joinToString("\n")
}

data class ParsedResume(
    val kind: DocumentKind,
    val name: String?,
    val contact: List<String>,
    val summary: List<String>,
    val skills: List<String>,
    val jobs: List<ResumeJob>,
    val education: List<String>,
    val languages: List<String>,
    val otherSections: Map<String, List<String>> = emptyMap()
)
typealias ParsedDocument = ParsedResume

data class DocumentAnswer(val text: String, val evidence: List<String>, val matchedTerms: List<String>, val found: Boolean)

/** Offline, bounded and extractive parser. All returned content is traceable to input text. */
class ResumeDocumentParser {
    fun parse(text: String): ParsedDocument {
        val lines = text.take(MAX_CHARS).replace('\r', '\n').lines().map { it.trim() }.filter { it.isNotBlank() }
        val sectionLines = linkedMapOf<String, MutableList<String>>()
        var section = ""
        val allLines = mutableListOf<Pair<String, String>>()
        for (line in lines) {
            val heading = canonicalHeading(line)
            if (heading != null) { section = heading; sectionLines.getOrPut(section) { mutableListOf() }; continue }
            allLines += section to line
            if (section.isNotEmpty()) sectionLines.getOrPut(section) { mutableListOf() } += line
        }
        val name = findName(lines, sectionLines)
        val contacts = lines.filter { EMAIL.containsMatchIn(it) || PHONE.containsMatchIn(it) || URL.containsMatchIn(it) }
            .distinct().take(8)
        val jobs = parseJobs(sectionLines["experience"].orEmpty())
        val resumeScore = (if (name != null) 1 else 0) + listOf("summary", "skills", "experience", "education", "languages").count { it in sectionLines } + if (contacts.isNotEmpty()) 1 else 0
        val kind = when {
            FORM_HINT.containsMatchIn(text.take(MAX_CHARS)) -> DocumentKind.FORM
            resumeScore >= 3 || jobs.isNotEmpty() -> DocumentKind.RESUME
            ARTICLE_HINT.containsMatchIn(text.take(MAX_CHARS)) -> DocumentKind.ARTICLE
            REPORT_HINT.containsMatchIn(text.take(MAX_CHARS)) -> DocumentKind.REPORT
            else -> DocumentKind.UNKNOWN
        }
        return ParsedResume(kind, name, contacts, sectionLines["summary"].orEmpty().toList(),
            sectionLines["skills"].orEmpty().flatMap(::items).distinct(), jobs,
            sectionLines["education"].orEmpty().toList(), sectionLines["languages"].orEmpty().flatMap(::items).distinct(),
            sectionLines.filterKeys { it !in CORE_SECTIONS }.mapValues { it.value.toList() })
    }

    /** Returns only directly relevant source evidence, with query terms visibly delimited. */
    fun answer(text: String, query: String): DocumentAnswer {
        val parsed = parse(text)
        val qTerms = tokens(query).distinct().filter { it !in QUERY_STOP }
        val asksName = qTerms.any { it in NAME_QUERY }
        val asksContact = qTerms.any { it in CONTACT_QUERY }
        val asksSkills = qTerms.any { it in SKILL_QUERY }
        val asksSummary = qTerms.any { it in SUMMARY_QUERY }
        val asksPrevious = qTerms.any { it in PREVIOUS_QUERY }
        val matchingEmployers = parsed.jobs.filter { job ->
            val companyTerms = tokens(job.company).filterNot { it in QUERY_STOP }
            val overlap = companyTerms.count { it in qTerms }
            query.contains(job.company, true) || (companyTerms.isNotEmpty() && overlap == companyTerms.size) ||
                (overlap == 1 && parsed.jobs.count { candidate ->
                    tokens(candidate.company).filterNot { it in QUERY_STOP }.any { it in qTerms }
                } == 1)
        }
        val queryEmployer = matchingEmployers.firstOrNull()
        val pool: List<String> = when {
            queryEmployer != null -> listOf(queryEmployer.evidence())
            asksPrevious -> parsed.jobs.drop(1).take(1).map(ResumeJob::evidence)
            asksSkills || parsed.skills.any { s -> qTerms.any { s.contains(it, true) } } -> parsed.skills
            asksSummary -> parsed.summary
            asksName && asksContact -> listOfNotNull(parsed.name) + parsed.contact
            asksName -> listOfNotNull(parsed.name)
            asksContact -> parsed.contact
            qTerms.any { it in EDUCATION_QUERY } -> parsed.education
            qTerms.any { it in LANGUAGE_QUERY } -> parsed.languages
            else -> buildList {
                parsed.jobs.forEach { add(it.evidence()) }; addAll(parsed.skills); addAll(parsed.summary); addAll(parsed.education); addAll(parsed.languages)
            }
        }
        val fieldQuery = asksName || asksContact || asksSkills || asksSummary || asksPrevious || qTerms.any { it in EDUCATION_QUERY || it in LANGUAGE_QUERY }
        val selectorTerms = qTerms.filterNot { it in FIELD_QUERY }
        val matches = when {
            queryEmployer != null || asksPrevious -> pool
            fieldQuery && selectorTerms.isEmpty() -> pool
            fieldQuery -> pool.filter { item -> selectorTerms.any { item.contains(it, true) } }.ifEmpty { pool }
            else -> pool.filter { item -> selectorTerms.any { item.contains(it, true) } }
        }
        if (matches.isEmpty()) return DocumentAnswer("No matching evidence was found in the provided document.", emptyList(), emptyList(), false)
        val selected = matches.distinct().take(if (queryEmployer != null) 1 else 4)
        val queryPhrase = selectorTerms.joinToString(" ").takeIf { phrase ->
            phrase.contains(' ') && selected.any { it.contains(phrase, true) }
        }
        val termsToHighlight = when {
            queryEmployer != null -> listOf(queryEmployer.company)
            asksPrevious -> parsed.jobs.drop(1).firstOrNull()?.let { listOf(it.company, it.role) }.orEmpty()
            queryPhrase != null -> listOf(queryPhrase)
            else -> qTerms.filter { term -> selected.any { it.contains(term, true) } }.sortedByDescending { it.length }
        }
        val highlighted = selected.map { markTerms(it, termsToHighlight) }
        return DocumentAnswer(highlighted.joinToString("\n"), selected, termsToHighlight, true)
    }

    private fun parseJobs(lines: List<String>): List<ResumeJob> {
        val result = mutableListOf<ResumeJob>()
        var current: ResumeJob? = null
        for (line in lines) {
            val headers = extractHeaders(line)
            if (headers.isNotEmpty()) {
                headers.forEach { h ->
                    current?.let(result::add)
                    current = h
                }
            } else if (current != null && (line.startsWith("-") || line.startsWith("•") || line.startsWith("*") || line.startsWith("▪"))) {
                val job = current!!
                current = job.copy(bullets = job.bullets + line.trimStart('-', '•', '*', '▪', ' ').trim())
            } else if (current != null && line.length > 12) {
                val job = current!!
                current = job.copy(bullets = job.bullets + line)
            }
        }
        current?.let(result::add)
        return result
    }

    private fun extractHeaders(raw: String): List<ResumeJob> {
        val parts = raw.split(Regex("\\s*\\|\\s*"))
        if (parts.size < 4) return emptyList()
        val headers = mutableListOf<ResumeJob>()
        var i = 0
        while (i + 3 < parts.size) {
            val dateAt = (i + 3 until minOf(i + 6, parts.size)).firstOrNull { DATE_HINT.containsMatchIn(parts[it]) } ?: break
            if (dateAt != i + 3) break
            val role = parts[i].trim(); val company = parts[i + 1].trim(); val place = parts[i + 2].trim(); val date = parts[i + 3].trim()
            if (role.isBlank() || company.isBlank() || role.length > 100 || company.length > 100) break
            headers += ResumeJob(role, company, place, date, emptyList())
            i += 4
        }
        return headers
    }

    private fun canonicalHeading(line: String): String? {
        val s = line.trim().trimEnd(':').lowercase()
        return when {
            s.matches(Regex("(work )?experience|employment( history)?|professional experience")) -> "experience"
            s.matches(Regex("summary|professional summary|profile|objective|about me")) -> "summary"
            s.matches(Regex("skills|technical skills|core competencies|key skills")) -> "skills"
            s.matches(Regex("education|academic background|qualifications")) -> "education"
            s.matches(Regex("languages|language skills")) -> "languages"
            s.matches(Regex("contact|contact information|personal details")) -> "contact"
            else -> null
        }
    }

    private fun findName(lines: List<String>, sections: Map<String, List<String>>): String? {
        val first = lines.take(5).firstOrNull { line ->
            line.length in 3..60 && NAME_LINE.matches(line) && !EMAIL.containsMatchIn(line) && !PHONE.containsMatchIn(line) && canonicalHeading(line) == null &&
                !line.contains("resume", true) && !line.contains("curriculum vitae", true)
        }
        return first?.takeIf { it.split(Regex("\\s+")).size in 2..4 && it.any(Char::isLetter) }
    }

    private fun items(line: String): List<String> = line.split(Regex("[,;•|]")).map { it.trim().trimStart('-', '*').trim() }.filter(String::isNotBlank)
    private fun tokens(value: String): List<String> = value.lowercase().split(Regex("[^\\p{L}\\p{N}.+#@-]+" )).filter(String::isNotBlank)
    private fun markTerms(value: String, terms: List<String>): String {
        val emails = EMAIL.findAll(value).toList()
        if (emails.isEmpty()) return markSegment(value, terms)
        val out = StringBuilder()
        var cursor = 0
        emails.forEach { email ->
            if (email.range.first >= cursor) {
                out.append(markSegment(value.substring(cursor, email.range.first), terms))
                out.append(email.value)
                cursor = email.range.last + 1
            }
        }
        if (cursor < value.length) out.append(markSegment(value.substring(cursor), terms))
        return out.toString()
    }

    private fun markSegment(value: String, terms: List<String>): String {
        var out = value
        terms.forEach { term -> out = out.replace(Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])")) { "⟦${it.value}⟧" } }
        return out
    }

    companion object {
        private const val MAX_CHARS = 200_000
        private val CORE_SECTIONS = setOf("summary", "skills", "experience", "education", "languages", "contact")
        private val DATE_HINT = Regex("(?i)(19|20)\\d{2}|present|current|ongoing")
        private val EMAIL = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        private val PHONE = Regex("(?:\\+?\\d[\\d ()-]{7,}\\d)")
        private val URL = Regex("(?i)https?://\\S+|www\\.\\S+")
        private val NAME_LINE = Regex("[\\p{Lu}][\\p{L}'-]+(?:\\s+[\\p{Lu}][\\p{L}'-]+){1,3}")
        private val NAME_QUERY = setOf("name", "named")
        private val CONTACT_QUERY = setOf("contact", "email", "phone", "telephone")
        private val SKILL_QUERY = setOf("skill", "skills", "technology", "technologies", "tool", "tools")
        private val SUMMARY_QUERY = setOf("summary", "profile", "objective")
        private val EDUCATION_QUERY = setOf("education", "degree", "school", "university")
        private val LANGUAGE_QUERY = setOf("language", "languages", "speak", "speaks")
        private val PREVIOUS_QUERY = setOf("previous", "former", "prior")
        private val FIELD_QUERY = NAME_QUERY + CONTACT_QUERY + SKILL_QUERY + SUMMARY_QUERY + EDUCATION_QUERY + LANGUAGE_QUERY
        private val FORM_HINT = Regex("(?i)\\b(check one|please complete|signature|date of birth|select one)\\b")
        private val ARTICLE_HINT = Regex("(?i)\\b(abstract|introduction|references|doi:)\\b")
        private val REPORT_HINT = Regex("(?i)\\b(executive summary|findings|recommendations|methodology|quarterly report)\\b")
        private val QUERY_STOP = setOf("what", "which", "who", "is", "are", "the", "for", "about", "show", "me", "tell", "find", "list", "please", "does", "did", "do", "have", "has", "worked", "work", "at", "on", "in", "document", "resume", "their", "my", "and", "was", "were", "his", "her", "they", "he", "she", "with", "using", "from", "s")
    }
}
